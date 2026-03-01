package de.dralle.asb;

public record GeneratorMessage(MessageType msgType,Integer line,Integer col,String msg) {

}
