package de.dralle.asb;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.jetbrains.annotations.NotNull;

import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.Header;

public class Main {
	public static void main(String[] args) throws ParseException {
		Options options = new Options();
		options.addOption("h", "help", false, "Show help");
		options.addOption("p", "port", true, "Port the server runs on");
		options.addOption("b", "bind", true, "Address to bind to");
		options.addOption("s", "storage", true, "Where to store cached files");

		DefaultParser parser = new DefaultParser();
		CommandLine cmd = parser.parse(options, args);

		int port = 7070;
		String bind = "127.0.0.1";
		String storagePath = "storage";
		if (cmd.hasOption("h")) {
			HelpFormatter formatter = new HelpFormatter();
			formatter.printHelp("antlr-server-backend", options);
		} else {
			if (cmd.hasOption("p")) {
				port = Integer.parseInt(cmd.getOptionValue("p"));
			}
			if (cmd.hasOption("b")) {
				bind = cmd.getOptionValue("b");
			}
			if (cmd.hasOption("s")) {
				storagePath = (cmd.getOptionValue("s"));
			}
			Path storagePathPath = Paths.get(storagePath);
			AntlrHandler handler = new AntlrHandler(storagePathPath);

			Javalin app = Javalin.create(config -> {
			    config.bundledPlugins.enableCors(cors -> {
			        cors.addRule(it -> {
			            it.anyHost(); // Entspricht "*"
			        });
			    });
			});

			
			// Routing
			app.put("/api/grammars/upload", ctx -> handler.uploadGrammar(ctx));
			app.put("/api/grammars/{name}/upload/", ctx -> handler.uploadGrammarOverwrite(ctx));
			app.post("/api/grammars/{name}/compile/", ctx -> handler.compileGrammar(ctx));
			app.post("/api/grammars/{name}/generate/", ctx -> handler.generateParserLexer(ctx));
			app.post("/api/grammars/{name}/rename/{newName}", ctx -> handler.renameGrammar(ctx));
			app.get("/api/grammars/list", ctx -> handler.listGrammars(ctx));
			app.get("/api/grammars/{name}/exists/", ctx -> handler.checkGrammarExists(ctx));
			app.get("/api/grammars/{name}/compiled/", ctx -> handler.checkGrammarIsCompiled(ctx));
			app.get("/api/grammars/{name}/generated/", ctx -> handler.isGrammarGenerated(ctx));
			app.get("/api/grammars/{name}/generated/errors", ctx -> handler.getLastGeneratorOutput(ctx));
			app.get("/api/grammars/{name}/get/", ctx -> handler.getGrammar(ctx));
			app.delete("/api/grammars/{name}/delete/", ctx -> handler.deleteGrammar(ctx));
			app.put("/api/parse/{name}/{startRule}", ctx -> handler.parse(ctx));
			app.get("/api/parse/{name}/tree/lisp", ctx -> handler.getTreeAsLisp(ctx));
			app.get("/api/parse/{name}/tree/svg", ctx -> handler.getTreeAsSvg(ctx));
			app.get("/api/parse/{name}/tree/errors", ctx -> handler.getLastParseErrors(ctx));
			app.get("/api/parse/{name}/input", ctx -> handler.getLastParsedContent(ctx));

			app.start(bind, port);
			System.out.println("Server is running at http://localhost:" + port + " (bound to " + bind
					+ "). Files are stored at " + storagePathPath.toAbsolutePath() + ".");

		}
	}
}
