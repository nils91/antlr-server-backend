package de.dralle.asb;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.ATNConfigSet;
import org.antlr.v4.runtime.dfa.DFA;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class ParserErrorListener extends BaseErrorListener {
	public List<ParserMessage> messages = new ArrayList<>();

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, 
                            int line, int charPositionInLine, String msg, RecognitionException e) {
       messages.add(new ParserMessage(ParserMessageType.SYNTAX, line, charPositionInLine, msg));
    }

    public List<ParserMessage> getErrors() { return messages; }

	

}