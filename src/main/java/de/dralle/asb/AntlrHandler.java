package de.dralle.asb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Trees;
import org.antlr.v4.tool.ANTLRToolListener;
import org.jetbrains.annotations.NotNull;

import com.fasterxml.jackson.databind.ObjectMapper;

import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import io.javalin.http.Context;

public class AntlrHandler {

	private Path storage = Paths.get("storage");

	public AntlrHandler() {

	}

	public AntlrHandler(Path p) {
		storage = p;
	}

	public void uploadGrammar(Context ctx) throws Exception {
		byte[] payload = ctx.bodyAsBytes();
		String text = new String(payload);
		String[] lines = text.split("\n");
		String grammarName = UUID.randomUUID().toString();
		for (String string : lines) {
			Pattern p = Pattern.compile("grammar\\s+(.+?)\\s*?(;|$)");
			Matcher m = p.matcher(string);
			if (m.find()) {
				grammarName = m.group(1);
			}
		}

		Path dir = storage.resolve(grammarName);
		Files.createDirectories(dir);
		Files.write(dir.resolve(grammarName + ".g4"), payload);
		ctx.status(201);
		ctx.json(grammarName);
	}

	public void parse(Context ctx) throws Exception {
		String name = ctx.pathParam("name");
		byte[] payload = ctx.bodyAsBytes();
		Path dir = storage.resolve(name);
		if (!dir.toFile().exists()) {
			ctx.status(404);
			return;
		}	
		Class<?> lexerClass = null;
		Class<?> parserClass = null;
		try (URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				getClass().getClassLoader())) {
			lexerClass = loader.loadClass(name + "Lexer");
			parserClass = loader.loadClass(name + "Parser");
		}catch(ClassNotFoundException e) {
			ctx.status(404);
			return;
		}
		if(lexerClass==null||parserClass==null) {
			ctx.status(500);
			return;
		}
		SyntaxErrorListener errorListener = new SyntaxErrorListener();

		Lexer lexer = (Lexer) lexerClass.getConstructor(CharStream.class)
				.newInstance(CharStreams.fromString(new String(payload)));
		lexer.removeErrorListeners();
		lexer.addErrorListener(errorListener);

		CommonTokenStream tokens = new CommonTokenStream(lexer);
		Parser parser = (Parser) parserClass.getConstructor(TokenStream.class).newInstance(tokens);
		parser.removeErrorListeners();
		parser.addErrorListener(errorListener);

		String startRuleName = ctx.pathParam("startRule");
		Method startRule = parserClass.getMethod(startRuleName);
		if(startRule==null) {
			ctx.status(400);
			return;
		}
		ParseTree tree = (ParseTree) startRule.invoke(parser);
		
		Path cacheFolder = Paths.get(dir.toString(), "cache");
		cacheFolder.toFile().mkdir();

		Files.write(cacheFolder.resolve("errors.json"), new ObjectMapper().writeValueAsBytes(errorListener.getErrors()));
		Files.writeString(cacheFolder.resolve("ast.txt"), tree.toStringTree());
		byte[] svgTree = generateTreeImage(parser, tree);
		Files.write(cacheFolder.resolve("ast.svg"),svgTree);
		Files.write(cacheFolder.resolve("input.txt"), payload);

		ctx.result(svgTree);
	}

	private byte[] generateTreeImage(org.antlr.v4.runtime.Parser parser, ParseTree tree) throws IOException {
		StringBuilder dot = new StringBuilder();
		dot.append("digraph G {\n");
		dot.append("  node [shape=none, fontname=\"Arial\"];\n");
		buildDot(tree, parser, dot, 0);
		dot.append("}");

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		Graphviz.fromString(dot.toString()).width(1000) // Optional: scale width
				.render(Format.SVG).toOutputStream(baos);

		return baos.toByteArray();
	}

	private int buildDot(ParseTree tree, org.antlr.v4.runtime.Parser parser, StringBuilder dot, int count) {
		int currentId = count;
		String label = Trees.getNodeText(tree, parser);
		// Escape quotes for DOT format
		label = label.replace("\"", "\\\"");

		dot.append(String.format("  n%d [label=\"%s\"];\n", currentId, label));

		for (int i = 0; i < tree.getChildCount(); i++) {
			int childId = count + 1;
			dot.append(String.format("  n%d -> n%d;\n", currentId, childId));
			count = buildDot(tree.getChild(i), parser, dot, childId);
		}
		return count;
	}

	public void listGrammars(@NotNull Context ctx) {
		List<String> nameList = new ArrayList<String>();
		File[] files = storage.toFile().listFiles();
		if (files != null) {
			for (File file : files) {
				if (file.isDirectory()) {
					nameList.add(file.getName());
				}
			}
		}
		ctx.json(nameList);
	}

	public void deleteGrammar(@NotNull Context ctx) {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		if(!grammarFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		deleteRecursive(grammarFolderPath);
		ctx.json(!grammarFolderPath.toFile().exists());
	}

	private int deleteRecursive(Path grammarFolderPath) {
		int cnt = 0;
		File[] sub = grammarFolderPath.toFile().listFiles();
		if (sub != null) {
			for (File file : sub) {
				cnt += deleteRecursive(file.toPath());
			}
		}
		if (grammarFolderPath.toFile().delete()) {
			cnt++;
		}
		return cnt;
	}

	public void checkGrammarExists(@NotNull Context ctx) {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path grammarFile = Paths.get(grammarFolderPath.toString(), name + ".g4");
		ctx.json(grammarFile.toFile().exists() + "");
	}

	public void checkGrammarIsCompiled(@NotNull Context ctx) {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path compileStatusFilePath = Paths.get(grammarFolderPath.toString(), name + ".compiled");
		if(!grammarFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		String compileResultFromFile=null;
		try {
			compileResultFromFile=Files.readString(compileStatusFilePath);
		} catch (IOException e) {
			
		}
		ctx.json("0".equals(compileResultFromFile));
	}

	public void compileGrammar(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path grammarFileName=grammarFolderPath.resolve(name+".g4");
		org.antlr.v4.Tool antlr = new org.antlr.v4.Tool(
				new String[] { grammarFileName.toString() });
		antlr.processGrammarsOnCommandLine();
		if (grammarFolderPath.toFile().exists()) {
			File[] subFiles = grammarFolderPath.toFile().listFiles();
			String grammarName = null;
			File grammarFile = null;
			for (File file2 : subFiles) {
				if (file2.getName().endsWith(".g4")) {
					grammarName = file2.getName().split("\\.")[0];
					grammarFile = file2;
				}
			}
			// 1. Run ANTLR Tool
			org.antlr.v4.Tool antlr = new org.antlr.v4.Tool(
					new String[] { grammarFolderPath.resolve(grammarFile.getName()).toString() });
			antlr.processGrammarsOnCommandLine();

			// 2. Compile Java Files
			JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
			List<File> files = Files.walk(grammarFolderPath).filter(p -> p.toString().endsWith(".java"))
					.map(Path::toFile).toList();
			int compileResult =compiler.run(null, null, null, files.stream().map(File::getAbsolutePath).toArray(String[]::new));

			Files.writeString(grammarFolderPath.resolve(grammarName + ".compiled"), compileResult+"");

			ctx.result(true + "");
		} else {
			ctx.result(false + "");
		}

	}

	public void uploadGrammarOverwrite(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		byte[] data = ctx.bodyAsBytes();
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		if (grammarFolderPath.toFile().exists()) {
			File[] subFiles = grammarFolderPath.toFile().listFiles();
			File grammarFile = null;
			for (File file2 : subFiles) {
				if (file2.getName().endsWith(".g4")) {
					grammarFile = file2;
				}
			}
			Files.write(grammarFolderPath.resolve(grammarFile.getName()), data);
			ctx.result(true + "");
		} else {
			ctx.result(false + "");
		}
	}

	public void getGrammar(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		if (grammarFolderPath.toFile().exists()) {
			File[] subFiles = grammarFolderPath.toFile().listFiles();
			File grammarFile = null;
			for (File file2 : subFiles) {
				if (file2.getName().endsWith(".g4")) {
					grammarFile = file2;
				}
			}
			ctx.result(Files.readString(grammarFile.toPath()));
		} else {
			ctx.result();
		}
	}

	public void getTreeAsLisp(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "ast.txt");
		ctx.result(Files.readString(grammarCacheFolderPath));
	}

	public void getTreeAsSvg(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "ast.svg");
		ctx.result(Files.readAllBytes(grammarCacheFolderPath));
	}

	public void getLastParseErrors(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "errors.txt");
		ctx.result(Files.readString(grammarCacheFolderPath));
	}

	public void getLastParsedContent(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "input.txt");
		ctx.result(Files.readString(grammarCacheFolderPath));
	}

	public void generateParserLexer(@NotNull Context ctx) {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path grammarFileName=grammarFolderPath.resolve(name+".g4");
		if(!grammarFileName.toFile().exists()) {
			ctx.status(404);
			return;
		}
		org.antlr.v4.Tool antlr = new org.antlr.v4.Tool(
				new String[] { grammarFileName.toString() });
		ANTLRGenerateListener gmc = new ANTLRGenerateListener();antlr.removeListeners();		
		antlr.addListener(gmc);
		antlr.processGrammarsOnCommandLine();
		List<GeneratorMessage> messages = gmc.getMessages();
		Path expectedParserFileName=grammarFolderPath.resolve(name+"Parser.java");
		Path expectedLexerFileName=grammarFolderPath.resolve(name+"Lexer.java");
	}

	public Object getLastGeneratorOutput(@NotNull Context ctx) {
		// TODO Auto-generated method stub
		return null;
	}

	public Object renameGrammar(@NotNull Context ctx) {
		// TODO Auto-generated method stub
		return null;
	}

	public Object isGrammarGenerated(@NotNull Context ctx) {
		// TODO Auto-generated method stub
		return null;
	}

}