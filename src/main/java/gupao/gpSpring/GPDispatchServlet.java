package gupao.gpSpring;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import gupao.gpSpring.annotations.GPAutowired;
import gupao.gpSpring.annotations.GPController;
import gupao.gpSpring.annotations.GPRequestMapping;
import gupao.gpSpring.annotations.GPService;

public class GPDispatchServlet extends HttpServlet{
	private Properties contextConfig=new Properties();
	private List<String> classNames=new ArrayList<String>();
	private Map<String,Object> ioc=new HashMap<String,Object>(1024);
	private Map<String,Method> handlerMapping=new HashMap<String,Method>(1024);
	
	@Override
	protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doDispatch(req, resp);
	}
	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		doDispatch(req,resp);
	}
	
	@Override
	public void init(ServletConfig config) {
		//1. 加载配置文件
		doLoadConfig(config.getInitParameter("contextConfigLocation"));
		
		//2. 扫描相关类
		doScanner(contextConfig.getProperty("scanPackage"));
		
		//3. 初始化扫描到的bean并放入到IOC容器中
		doInstance();
		
		//4. 依赖注入
		doAutowired();
		
		//5. 初始化HandlerMapping
		initHandlerMapping();
	}
	
	private void doLoadConfig(String initParameter) {
		System.out.println(initParameter);
		//从类路径下面将Spring主配置文件所在的路径，把内容读取到内存中
		InputStream fis=this.getClass().getClassLoader().getResourceAsStream(initParameter);
		try {
			contextConfig.load(fis);
		} catch (IOException e) {
			e.printStackTrace();
		}finally{
			if(fis!=null){
				try {
					fis.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
		System.out.println("scanPath="+contextConfig.getProperty("scanPackage"));
		
	}
	private void doScanner(String scanPackage) {
		//scanPackage=gupao.gpSpring 扫描的是class文件
		System.out.println("scanPackage="+scanPackage);
		URL url=this.getClass().getClassLoader().getResource("/"+scanPackage.replaceAll("\\.", "/"));
//		System.out.println("rul.path="+url.getPath());
//		System.out.println("url="+url);
		File classPath=new File(url.getFile());
		for(File file:classPath.listFiles()) {
			if(file.isDirectory()) {
				doScanner(scanPackage+"."+file.getName());
			}else {
				if(!(file.getName()).endsWith(".class")){ 
					continue;
				}
				String className=scanPackage+"."+file.getName().replace(".class", "");
				System.out.println("	扫描类："+className);
				this.classNames.add(className);
			}
		}
			
	}

	private void doInstance() {
		//利用反射把classNames中的Stirng的Class实例化,为DI做准备
		if(!classNames.isEmpty()) {
			for(String className:classNames) {
				try {
					Class<?> clazz=Class.forName(className);
					
					//只初始化带特定注解的类（实际上只有单例的才会在启动的时候进行初始化，原型的需要在使用时进行创建并初始化）
					if(clazz.isAnnotationPresent(GPController.class)) {
						Object instance=clazz.newInstance();
						//用首字母小写的className作为key
						String beanName=clazz.getSimpleName();
						System.out.println("simpleName="+beanName);
						ioc.put(beanName.substring(0, 1).toLowerCase()+beanName.substring(1), instance); 
					}else if(clazz.isAnnotationPresent(GPService.class)) {
						Object instance=clazz.newInstance();
						//1. 默认类名首字母小写
						//2. 自定义的beanName ，如@GPService("xxxxx")
						GPService service=clazz.getAnnotation(GPService.class);
						String beanName=service.value();
						//3. 根据类型自动赋值
						for(Class<?> i:clazz.getInterfaces()) {
							ioc.put(i.getName(), instance);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		System.out.println("ioc容器已完成初始化，有对象个数:"+ioc.size());
		for(String clazz:ioc.keySet()){
			System.out.println("	"+ioc.get(clazz));
		}
		
	}

	private void doAutowired() {
		System.out.println("进行注入...");
		if(!ioc.isEmpty()) {
			for(Map.Entry<String, Object> entry : ioc.entrySet()) {
				System.out.println("待注入对象:"+entry.getValue());
				//declared 代表所有的类型的字段，包括private protected public
				//如果是没有declared，则只会拿到public，和获取构造方法一样的
				Field[] fields=entry.getValue().getClass().getDeclaredFields();
				for(Field field : fields) {
					GPAutowired autowired=field.getAnnotation(GPAutowired.class);
					if(autowired==null){continue;} //说明该属性不需要注入
					
					System.out.println("	字段"+field.getName()+"需要注入");
					String beanName=autowired.value();
					
					field.setAccessible(true);
					if(beanName==null){
						beanName=field.getClass().getName();
					}
					try {
						field.set(entry.getValue(), ioc.get(beanName)); //把对象entry.getValue()的该字段设置为容器里面的对象
					} catch (IllegalArgumentException | IllegalAccessException e) {
						e.printStackTrace();
					}
				}
			}
		}
			
		
	}

	/**初始化url和Method的一对一对应关系*/
	private void initHandlerMapping() {
		if(!ioc.isEmpty()) {
			for(Map.Entry<String, Object> entry : ioc.entrySet()) {
				Class<?> clazz=entry.getValue().getClass();
				
				String baseUrl="";
				if(clazz.isAnnotationPresent(GPController.class)) {
					GPRequestMapping requestMapping=clazz.getAnnotation(GPRequestMapping.class);
					baseUrl=requestMapping.value();
				}
				for(Method method:clazz.getMethods()) {
					if(method.isAnnotationPresent(GPRequestMapping.class)) {
						GPRequestMapping requestMapping=method.getAnnotation(GPRequestMapping.class);	
						String url=baseUrl+"/"+requestMapping.value();
						handlerMapping.put(url.replaceAll("//", "/"), method);
					}
				}
			}
		}
		System.out.println("HandlerMapping完成，内容如下");
		for(String url:handlerMapping.keySet()){
			System.out.println("	url:"+url+"-"+handlerMapping.get(url).getName()+"()");
		}
	}

	private void doDispatch(HttpServletRequest req, HttpServletResponse resp) {
		//绝对路径 
		String url=req.getRequestURI();
		System.out.println("当前请求的url="+url);
		
		//相对路径
		String contextPath=req.getContextPath();
		url = url.replaceAll(contextPath,"").replaceAll("/+","/"); //map中存储的是/demo/query.*，但实际地址是/study/demo/query.*
		System.out.println("处理之后的url="+url);
		
		if(!this.handlerMapping.containsKey(url)) {
			try {
				resp.getWriter().write("404 Not FOUND-Mapping中午匹配");
			} catch (IOException e) {
				e.printStackTrace();
			}
		}else {
			Method method=this.handlerMapping.get(url);
			//通过反射拿到method所在class，再拿到ioc中的对象
			String beanName=method.getClass().getSimpleName();
			//首字母转小写
			Map<String,String[]> params=req.getParameterMap();
			try {
				method.invoke(ioc.get(toLowerFirstCase(beanName)), new Object[] {
						req,resp,params.get("name")[0]});
			} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				e.printStackTrace();
			}
		}
		
	}

	/**首字母小写*/
	private String toLowerFirstCase(String simpleName) {
		char [] chars = simpleName.toCharArray();
		//之所以加， 是因为大小写字母的 ASCII 码相差 32，
		// 而且大写字母的 ASCII 码要小于小写字母的 ASCII 码
		//在 Java 中， 对 char 做算学运算， 实际上就是对 ASCII 码做算学运算
		if(65<=chars[0] && chars[0]<=90){
			chars[0] += 32;
		}
		return String.valueOf(chars);
	}
	
}
