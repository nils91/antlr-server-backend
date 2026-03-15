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
		Object[] msgArgs = msg.getArgs();
		if(msgArgs!=null) {
			for (int i = 0; i < msgArgs.length; i++) {
				Object object = msgArgs[i];
				messages.add(new GeneratorMessage(MessageType.ERROR, msg.line, msg.charPosition, object.toString()));
			}
		}
		
	}

	@Override
	public void warning(ANTLRMessage msg) {
		Object[] msgArgs = msg.getArgs();
		if(msgArgs!=null) {
			for (int i = 0; i < msgArgs.length; i++) {
				Object object = msgArgs[i];
				messages.add(new GeneratorMessage(MessageType.WARNING, msg.line, msg.charPosition, object.toString()));
			}
		}
			}

	public List<GeneratorMessage> getMessages(){
		messages.sort(new GeneratorMessageComparator());
		return messages;
	}
}
