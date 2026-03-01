package de.dralle.asb;

import java.util.Comparator;

public class GeneratorMessageComparator implements Comparator<GeneratorMessage> {

	@Override
	public int compare(GeneratorMessage o1, GeneratorMessage o2) {
		if(o1.msgType()==o2.msgType()||(o1.msgType()!=null&&o1.msgType().equals(o2.msgType()))) {
			if(o1.line()==o2.line()||(o1.line()!=null&&o1.line().equals(o2.line()))) {
				if(o1.col()==o2.col()||(o1.col()!=null&&o1.col().equals(o2.col()))) {
					return 0;
				}else {
					if(o1.col()==null) {
						return 1;
					}else {
						return o1.col().compareTo(o2.col());
					}
				}
			}else {
				if(o1.line()==null) {
					return 1;
				}else {
					return o1.line().compareTo(o2.line());
				}
			}
		}else {
			if(o1.msgType()==null) {
				return 1;
			}else {
				return o1.msgType().compareTo(o2.msgType());
			}
		}
	}

}
