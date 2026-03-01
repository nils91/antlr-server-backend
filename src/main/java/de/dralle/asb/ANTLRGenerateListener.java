package de.dralle.asb;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ANTLRToolListener;

public class ANTLRGenerateListener implements ANTLRToolListener {
	public List<GeneratorMessage> messages = new ArrayList<>();

	@Override
	public void info(String msg) {
		messages.add(new GeneratorMessage(MessageType.INFO, null, null, msg));

	}

	@Override
	public void error(ANTLRMessage msg) {
		messages.add(new GeneratorMessage(MessageType.ERROR, msg.line, msg.charPosition, msg.toString()));
	}

	@Override
	public void warning(ANTLRMessage msg) {
		messages.add(new GeneratorMessage(MessageType.WARNING, msg.line, msg.charPosition, msg.toString()));
	}

	public List<GeneratorMessage> getMessages(){
		messages.sort(new GeneratorMessageComparator());
		return messages;
	}
}
