package de.dralle.asb;

public record ParserMessage(ParserMessageType msgType,Integer line,Integer col,String msg) {

}
