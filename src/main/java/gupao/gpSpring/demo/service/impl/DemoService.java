package gupao.gpSpring.demo.service.impl;

import gupao.gpSpring.demo.service.IDemoService;
import gupao.gpSpring.annotations.GPService;

/**
 * 核心业务逻辑
 */
@GPService
public class DemoService implements IDemoService{

	public String get(String name) {
		return "My name is " + name;
	}

}
