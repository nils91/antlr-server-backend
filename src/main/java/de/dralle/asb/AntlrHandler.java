package de.dralle.asb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
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

import com.fasterxml.jackson.core.JsonProcessingException;
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
		String grammarName = getGrammarNameFromGrammarFileContents(text);
		if (grammarName == null) {
			grammarName = UUID.randomUUID().toString();
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
		} catch (ClassNotFoundException e) {
			ctx.status(404);
			return;
		}
		if (lexerClass == null || parserClass == null) {
			ctx.status(500);
			return;
		}
		ParserErrorListener errorListener = new ParserErrorListener();

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
		if (startRule == null) {
			ctx.status(400);
			return;
		}
		ParseTree tree = (ParseTree) startRule.invoke(parser);

		Path cacheFolder = Paths.get(dir.toString(), "cache");
		cacheFolder.toFile().mkdir();

		Files.writeString(cacheFolder.resolve("errors.json"),
				new ObjectMapper().writeValueAsString(errorListener.getErrors()));
		Files.writeString(cacheFolder.resolve("ast.txt"), tree.toStringTree(parser));
		byte[] svgTree = generateTreeImage(parser, tree);
		Files.write(cacheFolder.resolve("ast.svg"), svgTree);
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
		if (!grammarFolderPath.toFile().exists()) {
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
		if (!grammarFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		Path lexerClassFilePath = Paths.get(grammarFolderPath.toString(), name + "Lexer.class");
		Path parserClassFilePath = Paths.get(grammarFolderPath.toString(), name + "Parser.class");
		ctx.json(lexerClassFilePath.toFile().exists() && parserClassFilePath.toFile().exists());
	}

	public void compileGrammar(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path lexerClassFilePath = Paths.get(grammarFolderPath.toString(), name + "Lexer.java");
		Path parserClassFilePath = Paths.get(grammarFolderPath.toString(), name + "Parser.java");
		if (!lexerClassFilePath.toFile().exists() || !parserClassFilePath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		Path outFileName = Paths.get(grammarFolderPath.toString(), name + ".out");
		Path errFileName = Paths.get(grammarFolderPath.toString(), name + ".err");
		FileOutputStream outFileStream = new FileOutputStream(outFileName.toFile());
		FileOutputStream errFileStream = new FileOutputStream(errFileName.toFile());
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		List<File> files = Files.walk(grammarFolderPath).filter(p -> p.toString().endsWith(".java")).map(Path::toFile)
				.toList();
		int compileResult = compiler.run(null, outFileStream, errFileStream,
				files.stream().map(File::getAbsolutePath).toArray(String[]::new));
		outFileStream.close();
		errFileStream.close();
		ctx.json(compileResult);
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
			ctx.json(true);
		} else {
			ctx.status(404);
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
			ctx.status(404);
		}
	}

	public void getTreeAsLisp(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "ast.txt");
		if (!grammarCacheFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		ctx.result(Files.readString(grammarCacheFolderPath));
	}

	public void getTreeAsSvg(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "ast.svg");
		if (!grammarCacheFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		ctx.result(Files.readAllBytes(grammarCacheFolderPath));
	}

	public void getLastParseErrors(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "errors.txt");
		if (!grammarCacheFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		ctx.result(Files.readString(grammarCacheFolderPath));
	}

	public void getLastParsedContent(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarCacheFolderPath = Paths.get(storage.toString(), name, "cache", "input.txt");
		if (!grammarCacheFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		ctx.result(Files.readString(grammarCacheFolderPath));
	}

	public void generateParserLexer(@NotNull Context ctx) throws JsonProcessingException, IOException {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path grammarFileName = grammarFolderPath.resolve(name + ".g4");
		if (!grammarFileName.toFile().exists()) {
			ctx.status(404);
			return;
		}
		org.antlr.v4.Tool antlr = new org.antlr.v4.Tool(new String[] { grammarFileName.toString() });
		ANTLRGenerateListener gmc = new ANTLRGenerateListener();
		antlr.removeListeners();
		antlr.addListener(gmc);
		antlr.processGrammarsOnCommandLine();
		List<GeneratorMessage> messages = gmc.getMessages();
		Path errorLogFile = grammarFolderPath.resolve(name + ".generated.errors.json");
		Files.writeString(errorLogFile, new ObjectMapper().writeValueAsString(messages));
		ctx.json(isParserGenerated(grammarFolderPath, name));
	}

	private boolean isParserGenerated(Path grammarFolderPath, String name) {
		Path expectedParserFileName = grammarFolderPath.resolve(name + "Parser.java");
		Path expectedLexerFileName = grammarFolderPath.resolve(name + "Lexer.java");
		return expectedLexerFileName.toFile().exists() && expectedParserFileName.toFile().exists();
	}

	public void getLastGeneratorOutput(@NotNull Context ctx) throws IOException {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path errorLogFile = grammarFolderPath.resolve(name + ".generated.errors.json");
		if (!errorLogFile.toFile().exists()) {
			ctx.status(404);
			return;
		}
		ctx.result(Files.readAllBytes(errorLogFile));
	}

	public void renameGrammar(@NotNull Context ctx) {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path oldGrammarFileName=grammarFolderPath.resolve(name+".g4");
		if (!grammarFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		String newName = ctx.pathParam("newName");
		Path newGrammarFolderPath = Paths.get(storage.toString(), newName);
		Path newGrammarFileName=grammarFolderPath.resolve(newName+".g4");
		if (newGrammarFolderPath.toFile().exists()) {
			ctx.status(400);
			return;
		}
		ctx.json(oldGrammarFileName.toFile().renameTo(newGrammarFileName.toFile())&&grammarFolderPath.toFile().renameTo(newGrammarFolderPath.toFile()));
	}

	public void isGrammarGenerated(@NotNull Context ctx) {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		if (!grammarFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		ctx.json(isParserGenerated(grammarFolderPath, name));
	}

	public void renameGrammarFromFile(@NotNull Context ctx) {
		String name = ctx.pathParam("name");
		Path grammarFolderPath = Paths.get(storage.toString(), name);
		Path oldGrammarFileName=grammarFolderPath.resolve(name+".g4");
		if (!grammarFolderPath.toFile().exists()) {
			ctx.status(404);
			return;
		}
		String newName = getGrammarNameFromGrammarFileContents(ctx.body());
		Path newGrammarFolderPath = Paths.get(storage.toString(), newName);
		Path newGrammarFileName=grammarFolderPath.resolve(newName+".g4");
		if (newGrammarFolderPath.toFile().exists()) {
			ctx.status(400);
			return;
		}
		ctx.json(oldGrammarFileName.toFile().renameTo(newGrammarFileName.toFile())&&grammarFolderPath.toFile().renameTo(newGrammarFolderPath.toFile()));
}

	private String getGrammarNameFromGrammarFileContents(String body) {
		String[] lines = body.split("\n");
		for (String string : lines) {
			Pattern p = Pattern.compile("grammar\\s+(.+?)\\s*?(;|$)");
			Matcher m = p.matcher(string);
			if (m.find()) {
				return m.group(1);
			}
		}
		return null;
	}
}