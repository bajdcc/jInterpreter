package com.bajdcc.LALR1.grammar.runtime;

import com.bajdcc.LALR1.grammar.Grammar;
import com.bajdcc.LALR1.grammar.runtime.RuntimeException.RuntimeError;
import com.bajdcc.LALR1.grammar.runtime.data.RuntimeArray;
import com.bajdcc.LALR1.grammar.runtime.data.RuntimeFuncObject;
import com.bajdcc.LALR1.grammar.runtime.data.RuntimeMap;
import com.bajdcc.LALR1.grammar.runtime.service.IRuntimePipeService;
import com.bajdcc.LALR1.grammar.runtime.service.IRuntimeService;
import com.bajdcc.LALR1.grammar.type.TokenTools;
import com.bajdcc.LALR1.interpret.module.*;
import com.bajdcc.LALR1.interpret.module.std.ModuleStdBase;
import com.bajdcc.LALR1.interpret.module.std.ModuleStdShell;
import com.bajdcc.LALR1.interpret.module.user.ModuleUserBase;
import com.bajdcc.LALR1.interpret.module.user.ModuleUserLisp;
import com.bajdcc.LALR1.interpret.module.user.ModuleUserWeb;
import com.bajdcc.LALR1.syntax.handler.SyntaxException;
import com.bajdcc.util.HashListMapEx;
import com.bajdcc.util.HashListMapEx2;
import com.bajdcc.util.lexer.error.RegexException;
import com.bajdcc.util.lexer.token.OperatorType;
import com.bajdcc.util.lexer.token.Token;
import com.bajdcc.util.lexer.token.TokenType;
import org.apache.log4j.Logger;
import sun.reflect.generics.reflectiveObjects.NotImplementedException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bajdcc.LALR1.grammar.runtime.RuntimeProcess.USER_PROC_PIPE_PREFIX;

/**
 * 【虚拟机】运行时自动机
 *
 * @author bajdcc
 */
public class RuntimeMachine implements IRuntimeStack, IRuntimeStatus, IRuntimeRing3 {

	private static Logger logger = Logger.getLogger("machine");
	private static IInterpreterModule[] modulesSystem;
	private static IInterpreterModule[] modulesUser;

	public enum Ring3Option {
		/**
		 * 自动保存运行日志文件，默认true
		 */
		LOG_FILE,
		/**
		 * 运行结果自动保留输出管道，默认false
		 */
		LOG_PIPE
	}

	private class Ring3Struct {
		int putHandle;
		boolean bSaveLogFile;
		boolean bSavePipeFile;
		public Set<Integer> handles;
		public int blockHandle;

		private Ring3Struct() {
			putHandle = -1;
			bSaveLogFile = true;
			bSavePipeFile = false;
			handles = new HashSet<>();
			blockHandle = -1;
		}

		void copyFrom(Ring3Struct ring3) {
			bSaveLogFile = ring3.bSaveLogFile;
			bSavePipeFile = ring3.bSavePipeFile;
		}

		void setOptionsBool(Ring3Option option, boolean flag) {
			switch (option) {
				case LOG_FILE:
					bSaveLogFile = flag;
					break;
				case LOG_PIPE:
					bSavePipeFile = flag;
					break;
				default:
					throw new NotImplementedException();
			}
		}

		boolean isOptionsBool(Ring3Option option) {
			switch (option) {
				case LOG_FILE:
					return bSaveLogFile;
				case LOG_PIPE:
					return bSavePipeFile;
			}
			throw new NotImplementedException();
		}
	}

	private Ring3Struct ring3Struct;

	private HashListMapEx<String, RuntimeCodePage> pageMap = new HashListMapEx<>();
	private Map<String, List<RuntimeCodePage>> pageRefer = new HashMap<>();
	private Map<String, RuntimeCodePage> codeCache = new HashMap<>();
	private List<RuntimeObject> stkYieldData = new ArrayList<>();
	private HashListMapEx2<String, Integer> stkYieldMap = new HashListMapEx2<>();
	private RuntimeStack currentStack = null;
	private List<RuntimeStack> stack = new ArrayList<>();
	private RuntimeCodePage currentPage;
	private String pageName;
	private String name;
	private String description;
	private RuntimeProcess process;
	private int ring;
	private int pid;
	private int parentId;
	private int triesCount;
	private boolean debug = false;

	public RuntimeMachine(int ring, RuntimeProcess process) throws Exception {
		if (modulesSystem == null) {
			logger.debug("Loading modules...");
			modulesSystem = new IInterpreterModule[]{
					ModuleBase.getInstance(),
					ModuleMath.getInstance(),
					ModuleList.getInstance(),
					ModuleString.getInstance(),
					ModuleProc.getInstance(),
					ModuleFunction.getInstance(),
					ModuleUI.getInstance(),
					ModuleTask.getInstance(),
					ModuleRemote.getInstance(),
					ModuleLisp.getInstance(),
					ModuleNet.getInstance(),
					ModuleFile.getInstance(),
					ModuleClass.getInstance(),
					ModuleStdBase.getInstance(),
					ModuleStdShell.getInstance(),
			};
		}

		if (modulesUser == null) {
			logger.debug("Loading user modules...");
			modulesUser = new IInterpreterModule[]{
					ModuleUserBase.getInstance(),
					ModuleUserWeb.getInstance(),
					ModuleUserLisp.getInstance()
			};
		}
		this.process = process;
		this.ring = ring;
		this.stack.add(new RuntimeStack());
		this.refreshStack();
		if (ring < 3) {
			for (IInterpreterModule module : modulesSystem) {
				process.getService().getFileService().addVfs(module.getModuleName(), module.getModuleCode());
				try {
					run(module.getModuleName(), module.getCodePage());
				} catch (SyntaxException e) {
					e.setPageName(module.getModuleName());
					e.setFileName(module.getClass().getSimpleName() + ".txt");
					throw e;
				}
			}
		} else {
			for (IInterpreterModule module : modulesUser) {
				process.getService().getFileService().addVfs(module.getModuleName(), module.getModuleCode());
				try {
					run(module.getModuleName(), module.getCodePage());
				} catch (SyntaxException e) {
					e.setPageName(module.getModuleName());
					e.setFileName(module.getClass().getSimpleName() + ".txt");
					throw e;
				}
			}
		}
	}

	public RuntimeMachine() throws Exception {
		this(0, null);
	}

	public RuntimeMachine(String name, int ring, int id, int parentId, RuntimeProcess process) throws Exception {
		this(ring, process);
		this.name = name;
		this.description = ring == 3 ? ("user proc") : "none";
		this.pid = id;
		this.parentId = parentId;
		if (ring == 3) {
			ring3Struct = new Ring3Struct();
			ring3Struct.putHandle = process.getService().getPipeService().create(USER_PROC_PIPE_PREFIX + pid, name);
		}
	}

	private void refreshStack() {
		currentStack = stack.get(stack.size() - 1);
	}

	/**
	 * FORK进程
	 * @param machine 被FORK的进程
	 */
	public void copyFrom(RuntimeMachine machine) throws RuntimeException {
		assert (machine.ring == 3);
		ring3Struct.copyFrom(machine.ring3Struct);
		pageMap = machine.pageMap.copy();
		pageRefer = machine.pageRefer.entrySet().stream().collect(Collectors.toMap(Entry::getKey, a -> new ArrayList<>(a.getValue())));
		codeCache = new HashMap<>(machine.codeCache);
		stkYieldData = new ArrayList<>(machine.stkYieldData);
		stkYieldMap = machine.stkYieldMap.copy();
		stack = machine.stack.stream().map(RuntimeStack::copy).collect(Collectors.toList());
		currentStack = stack.get(machine.stack.indexOf(machine.currentStack));
		currentPage = machine.currentPage;
		triesCount = machine.triesCount;
		store(new RuntimeObject(BigInteger.valueOf(-1)));
		opReturn();
	}

	public void run(String name, InputStream input) throws Exception {
		run(name, RuntimeCodePage.importFromStream(input));
	}

	@Override
	public void runPage(String name) throws Exception {
		BufferedReader br = new BufferedReader(new FileReader(name));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = br.readLine()) != null) {
			sb.append(line);
			sb.append(System.lineSeparator());
		}
		br.close();
		logger.debug("Loading file: " + name);
		Grammar grammar = new Grammar(sb.toString());
		run(name, grammar.getCodePage());
	}

	@Override
	public int runProcess(String name) throws Exception {
		BufferedReader br = new BufferedReader(new FileReader(name));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = br.readLine()) != null) {
			sb.append(line);
			sb.append(System.lineSeparator());
		}
		br.close();
		logger.debug("Loading file: " + name);
		Grammar grammar = new Grammar(sb.toString());
		return process.createProcess(pid, 0, name, grammar.getCodePage(), 0, null);
	}

	@Override
	public int runProcessX(String name) throws Exception {
		RuntimeCodePage page = process.getPage(name);
		if (page == null) {
			return -1;
		}
		return process.createProcess(pid, 0, name, page, 0, null);
	}

	@Override
	public int runUsrProcess(String name) throws Exception {
		BufferedReader br = new BufferedReader(new FileReader(name));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = br.readLine()) != null) {
			sb.append(line);
			sb.append(System.lineSeparator());
		}
		br.close();
		Grammar grammar = new Grammar(sb.toString());
		return process.createProcess(pid, 1, name, grammar.getCodePage(), 0, null);
	}

	@Override
	public int runUsrProcessX(String name) throws Exception {
		RuntimeCodePage page = process.getPage(name);
		if (page == null) {
			return -1;
		}
		return process.createProcess(pid, 1, name, page, 0, null);
	}

	public void add(String name, RuntimeCodePage page) {
		if (pageName != null && pageName.equals(name))
			return;
		if (pageMap.contains(name)) {
			warn(RuntimeError.DUP_PAGENAME, "代码页 " + pageName + " 加载 " + name);
		}
		pageMap.add(name, page);
		pageRefer.put(name, new ArrayList<>());
		pageRefer.get(name).add(page);
		page.getInfo().getDataMap().put("name", name);
	}

	public void run(String name, RuntimeCodePage page) throws Exception {
		add(name, page);
		currentPage = page;
		currentStack.reg.pageId = name;
		currentStack.reg.execId = 0;
		switchPage();
		runInsts();
	}

	private void runInsts() throws Exception {
		while (runByStep()) ;
	}

	public void initStep(String name, RuntimeCodePage page, List<RuntimeCodePage> refers, int pc, RuntimeObject obj) throws Exception {
		add(name, page);
		currentPage = page;
		currentStack.reg.pageId = name;
		currentStack.reg.execId = -1;
		switchPage();
		if (refers != null)
			pageRefer.get(pageName).addAll(refers);
		opOpenFunc();
		if (obj != null) {
			opPushObj(obj);
			opPushArgs();
		}
		opPushPtr(pc);
		opCall();
	}

	public int runStep() throws Exception {
		RuntimeInst inst = RuntimeInst.values()[currentInst()];
		if (inst == RuntimeInst.ihalt) {
			if (ring < 3)
				process.destroyProcess(pid);
			return 2;
		}
		return runByStep() ? 0 : 1;
	}

	private boolean runByStep() throws Exception {
		RuntimeInst inst = RuntimeInst.values()[currentInst()];
		if (inst == RuntimeInst.ihalt) {
			return false;
		}
		if (debug) {
			System.err.println();
			System.err.print(currentStack.reg.execId + ": " + inst.toString());
		}
		OperatorType op = TokenTools.ins2op(inst);
		nextInst();
		if (op != null) {
			if (!RuntimeTools.calcOp(currentStack.reg, inst, this)) {
				err(RuntimeError.UNDEFINED_CONVERT, op.getName());
			}
		} else {
			if (!RuntimeTools.calcData(currentStack.reg, inst, this)) {
				if (!RuntimeTools.calcJump(currentStack.reg, inst, this)) {
					err(RuntimeError.WRONG_INST, inst.toString());
				}
			}
		}
		if (debug) {
			System.err.println();
			System.err.print(currentStack.toString());
			System.err.print("协程栈：");
			System.err.print(stkYieldData.toString());
			System.err.println();
		}
		return true;
	}

	@Override
	public RuntimeObject load() throws RuntimeException {
		if (currentStack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		return currentStack.popData();
	}

	@Override
	public void store(RuntimeObject obj) throws RuntimeException {
		currentStack.pushData(obj);
	}

	private RuntimeObject dequeue() throws RuntimeException {
		if (stkYieldData.isEmpty()) {
			err(RuntimeError.NULL_QUEUE);
		}
		RuntimeObject obj = stkYieldData.get(stkYieldData.size() - 1);
		stkYieldData.remove(stkYieldData.size() - 1);
		return obj;
	}

	private void enqueue(RuntimeObject obj) {
		stkYieldData.add(obj);
	}

	public RuntimeObject top() throws RuntimeException {
		if (currentStack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		return currentStack.top();
	}

	private int loadInt() throws RuntimeException {
		RuntimeObject obj = load();
		if (obj.getType() != RuntimeObjectType.kPtr) {
			err(RuntimeError.WRONG_OPERATOR, RuntimeObjectType.kInt.getName() + " " + obj.toString());
		}
		return (int) obj.getObj();
	}

	private String loadString() throws RuntimeException {
		RuntimeObject obj = load();
		if (obj.getType() != RuntimeObjectType.kString) {
			err(RuntimeError.WRONG_OPERATOR, RuntimeObjectType.kInt.getName() + " " + obj.toString());
		}
		return String.valueOf(obj.getObj());
	}

	private boolean loadBool() throws RuntimeException {
		RuntimeObject obj = load();
		if (obj.getType() != RuntimeObjectType.kBool) {
			err(RuntimeError.WRONG_OPERATOR, RuntimeObjectType.kBool.getName() + " " + obj.toString());
		}
		return (boolean) obj.getObj();
	}

	private boolean loadBoolRetain() throws RuntimeException {
		RuntimeObject obj = top();
		if (obj.getType() != RuntimeObjectType.kBool) {
			err(RuntimeError.WRONG_OPERATOR, RuntimeObjectType.kBool.getName() + " " + obj.toString());
		}
		return (boolean) obj.getObj();
	}

	@Override
	public void push() throws RuntimeException {
		RuntimeObject obj = new RuntimeObject(current());
		store(obj);
		next();
	}

	@Override
	public void pop() throws RuntimeException {
		if (currentStack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		currentStack.popData();
	}

	private void nextInst() throws RuntimeException {
		currentStack.reg.execId++;
		if (isEOF()) {
			err(RuntimeError.WRONG_CODEPAGE);
		}
	}

	private void next() throws RuntimeException {
		if (isDebug()) {
			System.err.print(" " + current());
		}
		currentStack.reg.execId += 4;
		if (isEOF()) {
			err(RuntimeError.WRONG_CODEPAGE);
		}
	}

	@Override
	public void err(RuntimeError type) throws RuntimeException {
		System.err.println(currentStack);
		throw new RuntimeException(type, currentStack.reg.execId, type.getMessage() + "\n\n[ CODE ]\n" + currentPage.getDebugInfoByInc(currentStack.reg.execId));
	}

	@Override
	public void err(RuntimeError type, String message) throws RuntimeException {
		System.err.println(currentStack);
		throw new RuntimeException(type, currentStack.reg.execId, type.getMessage() + " " + message + "\n\n[ CODE ]\n" + currentPage.getDebugInfoByInc(currentStack.reg.execId));
	}

	public void errRT(RuntimeError type, String message) throws RuntimeException {
		System.err.println(currentStack);
		throw new RuntimeException(type, currentStack.reg.execId, message);
	}

	@Override
	public void warn(RuntimeError type, String message) {
		logger.warn(String.format("#%03d [%s] %s %s", pid, pageName, type.getMessage(), message));
	}

	@Override
	public int createProcess(RuntimeFuncObject func) throws Exception {
		return process.createProcess(pid, 0, func.getPage(), pageMap.get(func.getPage()), func.getAddr(), null);
	}

	@Override
	public int createProcess(RuntimeFuncObject func, RuntimeObject obj) throws Exception {
		return process.createProcess(pid, 0, func.getPage(), pageMap.get(func.getPage()), func.getAddr(), obj);
	}

	@Override
	public int createUsrProcess(RuntimeFuncObject func) throws Exception {
		return process.createProcess(pid, 1, func.getPage(), pageMap.get(func.getPage()), func.getAddr(), null);
	}

	@Override
	public int createUsrProcess(RuntimeFuncObject func, RuntimeObject obj) throws Exception {
		return process.createProcess(pid, 1, func.getPage(), pageMap.get(func.getPage()), func.getAddr(), obj);
	}

	@Override
	public List<RuntimeCodePage> getPageRefers(String page) {
		return pageRefer.get(page);
	}

	@Override
	public int getPid() {
		return pid;
	}

	@Override
	public int getParentPid() {
		return parentId;
	}

	@Override
	public int getPriority() {
		return process.getPriority(pid);
	}

	@Override
	public boolean setPriority(int priority) {
		return process.setPriority(pid, priority);
	}

	@Override
	public IRuntimeService getService() {
		return process.getService();
	}

	@Override
	public int sleep() {
		return 0;
	}

	@Override
	public List<Integer> getUsrProcs() {
		return process.getUsrProcs();
	}

	@Override
	public List<Integer> getSysProcs() {
		return process.getSysProcs();
	}

	@Override
	public List<Integer> getAllProcs() {
		return process.getAllProcs();
	}

	@Override
	public Object[] getProcInfo() {
		String funcName = currentStack.getFuncSimpleName();
		return new Object[]{
				process.isBlock(pid) ? " " : "*",
				String.valueOf(ring),
				String.valueOf(pid),
				name,
				funcName,
				description,
		};
	}

	@Override
	public String getPage() {
		return name;
	}

	@Override
	public void setProcDesc(String desc) {
		description = desc;
	}

	@Override
	public boolean isRing3() {
		return ring3Struct != null;
	}

	@Override
	public int exec(String code) throws Exception {
		try {
			Grammar grammar = new Grammar(code);
			return process.createProcess(pid, 3, name, grammar.getCodePage(), 0, null);
		} catch (RegexException e) {
			e.printStackTrace();
			errRT(RuntimeError.THROWS_EXCEPTION, e.getPosition() + ", " + e.getMessage());
		} catch (SyntaxException e) {
			e.printStackTrace();
			errRT(RuntimeError.THROWS_EXCEPTION, String.format("%s %s %s",
					e.getPosition(), e.getMessage(), e.getInfo()));
		}
		return -1;
	}

	@Override
	public int exec_file(String filename, String code) throws Exception {
		try {
			RuntimeCodePage page;
			if (codeCache.containsKey(filename))
				page = codeCache.get(filename);
			else
				page = new Grammar(code).getCodePage();
			return process.createProcess(pid, 3, filename.substring(1), page, 0, null);
		} catch (RegexException e) {
			e.printStackTrace();
			errRT(RuntimeError.THROWS_EXCEPTION, e.getPosition() + ", " + e.getMessage());
		} catch (SyntaxException e) {
			e.printStackTrace();
			errRT(RuntimeError.THROWS_EXCEPTION, String.format("%s %s\n%s",
					e.getPosition(), e.getMessage(), e.getInfo()));
		}
		return -1;
	}

	@Override
	public void put(String text) {
		final IRuntimePipeService pipe = process.getService().getPipeService();
		for (int i = 0; i < text.length(); i++) {
			pipe.write(ring3Struct.putHandle, text.charAt(i));
		}
	}

	@Override
	public void setOptionsBool(Ring3Option option, boolean flag) {
		ring3Struct.setOptionsBool(option, flag);
	}

	@Override
	public boolean isOptionsBool(Ring3Option option) {
		return ring3Struct.isOptionsBool(option);
	}

	@Override
	public void addHandle(int id) {
		ring3Struct.handles.add(id);
	}

	@Override
	public void removeHandle(int id) {
		ring3Struct.handles.remove(id);
	}

	@Override
	public Set<Integer> getHandles() {
		return ring3Struct.handles;
	}

	@Override
	public void setBlockHandle(int id) {
		ring3Struct.blockHandle = id;
	}

	@Override
	public int getBlockHandle() {
		return ring3Struct.blockHandle;
	}

	@Override
	public int fork() throws Exception {
		return process.createProcess(pid, ring, "fork", currentPage, -1, null);
	}

	@Override
	public RuntimeObject getFuncArgs(int index) {
		return currentStack.getFuncArgs(index);
	}

	@Override
	public int getFuncArgsCount() {
		return currentStack.getFuncArgsCount1();
	}

	@Override
	public IRuntimeRing3 getRing3() {
		return this;
	}

	@Override
	public IRuntimeRing3 getRing3(int pid) {
		return process.getRing3(pid);
	}

	@Override
	public RuntimeArray getAllDocs() {
		RuntimeArray array = new RuntimeArray();
		int i = 1;
		for (IInterpreterModule module : Stream.of(modulesSystem, modulesUser)
				.flatMap(Arrays::stream).collect(Collectors.toList())) {
			try {
				for (RuntimeArray arr : module.getCodePage().getInfo().getExternFuncList()) {
					arr.insert(0, new RuntimeObject(BigInteger.valueOf(i++)));
					arr.insert(1, new RuntimeObject(module.getModuleName()));
					array.add(new RuntimeObject(arr));
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return array;
	}

	@Override
	public Object[] getProcInfoById(int id) {
		return process.getProcInfoById(id);
	}

	private void switchPage() throws RuntimeException {
		if (!currentStack.reg.pageId.isEmpty()) {
			currentPage = pageMap.get(currentStack.reg.pageId);
			pageName = currentPage.getInfo().getDataMap().get("name").toString();
		} else {
			err(RuntimeError.WRONG_CODEPAGE);
		}
	}

	private Byte getInst(int pc) throws RuntimeException {
		List<Byte> code = currentPage.getInsts();
		if (pc < 0 || pc >= code.size()) {
			err(RuntimeError.WRONG_INST, String.valueOf(pc));
		}
		return code.get(pc);
	}

	private Byte currentInst() throws RuntimeException {
		if (currentStack.reg.execId != -1) {
			return getInst(currentStack.reg.execId);
		} else {
			return (byte) RuntimeInst.ihalt.ordinal();
		}
	}

	private int current() throws RuntimeException {
		int op = 0;
		byte b;
		for (int i = 0; i < 4; i++) {
			b = getInst(currentStack.reg.execId + i);
			op += (b & 0xFF) << (8 * i);
		}
		return op;
	}

	private boolean isEOF() {
		return currentStack.reg.execId < 0
				|| currentStack.reg.execId >= currentPage.getInsts().size();
	}

	private RuntimeObject fetchFromGlobalData(int index)
			throws RuntimeException {
		if (index < 0 || index >= currentPage.getData().size()) {
			err(RuntimeError.WRONG_OPERATOR, String.valueOf(index));
		}
		return new RuntimeObject(currentPage.getData().get(index));
	}

	@Override
	public void opLoad() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = fetchFromGlobalData(idx);
		if (obj.getSymbol() == null)
			obj.setSymbol(currentPage.getData().get(idx));
		currentStack.pushData(obj);
	}

	@Override
	public void opLoadFunc() throws RuntimeException {
		int idx = loadInt();
		RuntimeFuncObject func = new RuntimeFuncObject(pageName, idx);
		RuntimeObject obj = new RuntimeObject(func);
		int envSize = loadInt();
		FOR_LOOP:
		for (int i = 0; i < envSize; i++) {
			int id = loadInt();
			if (id == -1) {
				id = loadInt();
				String name = fetchFromGlobalData(id).getObj().toString();
				IRuntimeDebugValue value = currentPage.getInfo().getValueCallByName(name);
				if (value != null) {
					func.addEnv(id, value.getRuntimeObject());
					continue;
				}
				int index = currentPage.getInfo().getAddressOfExportFunc(name);
				if (index != -1) {
					func.addEnv(id, new RuntimeObject(new RuntimeFuncObject(pageName, index)));
					continue;
				}
				List<RuntimeCodePage> refers = pageRefer.get(currentPage.getInfo()
						.getDataMap().get("name").toString());
				for (RuntimeCodePage page : refers) {
					value = page.getInfo().getValueCallByName(name);
					if (value != null) {
						func.addEnv(id, value.getRuntimeObject());
						continue FOR_LOOP;
					}
					index = page.getInfo().getAddressOfExportFunc(name);
					if (index != -1) {
						func.addEnv(id, new RuntimeObject(new RuntimeFuncObject(page.getInfo()
								.getDataMap().get("name").toString(), index)));
						continue FOR_LOOP;
					}
				}
				err(RuntimeError.WRONG_LOAD_EXTERN, name);
			} else {
				func.addEnv(id, currentStack.findVariable(func.getPage(), id));
			}
		}
		currentStack.pushData(obj);
	}

	@Override
	public void opReloadFunc() throws RuntimeException {
		int idx = loadInt();
		RuntimeFuncObject func = new RuntimeFuncObject(currentStack.reg.pageId, currentStack.reg.execId);
		RuntimeObject obj = new RuntimeObject(func);
		obj.setSymbol(currentPage.getData().get(idx));
		currentStack.storeVariableDirect(idx, obj);
	}

	@Override
	public void opStore() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = load();
		RuntimeObject target = currentStack.findVariable(pageName, idx);
		if (target == null) {
			err(RuntimeError.WRONG_OPERATOR);
		}
		target.copyFrom(obj);
		store(target);
	}

	@Override
	public void opStoreDirect() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = load();
		if (obj.getSymbol() == null)
			obj.setSymbol(currentPage.getData().get(idx));
		currentStack.storeVariableDirect(idx, obj);
		store(obj);
	}

	@Override
	public void opOpenFunc() throws RuntimeException {
		if (!currentStack.pushFuncData()) {
			err(RuntimeError.STACK_OVERFLOW);
		}
	}

	@Override
	public void opLoadArgs() throws RuntimeException {
		int idx = current();
		next();
		if (idx < 0 || idx >= currentStack.getFuncArgsCount()) {
			err(RuntimeError.WRONG_ARGINVALID, currentStack.getFuncSimpleName() + " has " +
					(currentStack.getFuncArgsCount() - 1) + " args but got " + String.valueOf(idx));
		}
		store(currentStack.loadFuncArgs(idx));
	}

	@Override
	public void opPushArgs() throws RuntimeException {
		RuntimeObject obj = load();
		if (!currentStack.pushFuncArgs(obj)) {
			err(RuntimeError.ARG_OVERFLOW, obj.toString());
		}
	}

	@Override
	public void opReturn() throws RuntimeException {
		if (currentStack.isEmptyStack()) {
			err(RuntimeError.NULL_STACK);
		}
		if (currentStack.isYield()) {
			clearYieldStack();
		}
		currentStack.opReturn(currentStack.reg);
		switchPage();
	}

	@Override
	public void opCall() throws RuntimeException {
		int address = loadInt();
		currentStack.opCall(address, pageName, currentStack.reg.execId, pageName, currentPage
				.getInfo().getFuncNameByAddress(address));
		currentStack.reg.execId = address;
		currentStack.reg.pageId = pageName;
	}

	@Override
	public void opPushNull() throws RuntimeException {
		store(new RuntimeObject(null));
	}

	@Override
	public void opPushZero() throws RuntimeException {
		store(new RuntimeObject(0));
	}

	@Override
	public void opPushNan() throws RuntimeException {
		store(new RuntimeObject(null, RuntimeObjectType.kNan));
	}

	@Override
	public void opPushPtr(int pc) throws RuntimeException {
		store(new RuntimeObject(pc));
	}

	@Override
	public void opPushObj(RuntimeObject obj) throws RuntimeException {
		store(obj);
	}

	@Override
	public void opLoadVar() throws RuntimeException {
		int idx = loadInt();
		store(RuntimeObject.createObject((currentStack.findVariable(pageName, idx))));
	}

	@Override
	public void opJump() throws RuntimeException {
		currentStack.reg.execId = current();
	}

	@Override
	public void opJumpBool(boolean bool) throws RuntimeException {
		boolean tf = loadBool();
		if (tf == bool) {
			currentStack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpBoolRetain(boolean bool) throws RuntimeException {
		boolean tf = loadBoolRetain();
		if (tf == bool) {
			currentStack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpZero(boolean bool) throws RuntimeException {
		int val = loadInt();
		if ((val == 0) == bool) {
			currentStack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpYield() throws RuntimeException {
		String hash = RuntimeTools.getYieldHash(getLastStack(),
				currentStack.getFuncLevel(), pageName, currentStack.reg.execId - 1);
		if (stkYieldMap.contains(hash)) {
			currentStack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opJumpNan() throws RuntimeException {
		RuntimeObject obj = top();
		if (obj.getType() == RuntimeObjectType.kNan) {
			currentStack.reg.execId = current();
		} else {
			next();
		}
	}

	@Override
	public void opImport() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = fetchFromGlobalData(idx);
		String name = obj.getObj().toString();
		if (ring == 3 && !name.startsWith("user.")) {
			err(RuntimeError.WRONG_IMPORT, name);
		}
		RuntimeCodePage page = pageMap.get(name);
		if (page == null) {
			err(RuntimeError.WRONG_IMPORT, name);
		}
		pageRefer.get(pageName).add(page);
	}

	@Override
	public void opLoadExtern() throws RuntimeException {
		int idx = loadInt();
		RuntimeObject obj = fetchFromGlobalData(idx);
		String name = obj.getObj().toString();
		IRuntimeDebugValue value = currentPage.getInfo().getValueCallByName(name);
		if (value != null) {
			currentStack.pushData(value.getRuntimeObject());
			return;
		}
		int index = currentPage.getInfo().getAddressOfExportFunc(name);
		if (index != -1) {
			currentStack.pushData(new RuntimeObject(new RuntimeFuncObject(pageName, index)));
			return;
		}
		List<RuntimeCodePage> refers = pageRefer.get(currentPage.getInfo()
				.getDataMap().get("name").toString());
		for (RuntimeCodePage page : refers) {
			value = page.getInfo().getValueCallByName(name);
			if (value != null) {
				currentStack.pushData(value.getRuntimeObject());
				return;
			}
			index = page.getInfo().getAddressOfExportFunc(name);
			if (index != -1) {
				currentStack.pushData(new RuntimeObject(new RuntimeFuncObject(page.getInfo()
						.getDataMap().get("name").toString(), index)));
				return;
			}
		}
		err(RuntimeError.WRONG_LOAD_EXTERN, name);
	}

	@Override
	public void opCallExtern(boolean invoke) throws Exception {
		int idx = loadInt();
		String name = "";
		if (invoke) {
			RuntimeObject obj = null;
			if (idx == -1) { // call (call exp) (args...)
				obj = load();
			} else {
				for (int i = currentStack.getLevel(); i >= 0 && obj == null; i = currentStack.getParent()) {
					obj = stack.get(i).findVariable(pageName, idx);
				}
			}
			if (obj == null || obj.getType() == null) {
				err(RuntimeError.WRONG_LOAD_EXTERN, String.valueOf(idx));
			} else if (obj.getType() == RuntimeObjectType.kFunc) {
				RuntimeFuncObject func = (RuntimeFuncObject) obj.getObj();
				Map<Integer, RuntimeObject> env = func.getEnv();
				if (env != null) {
					for (Entry<Integer, RuntimeObject> entry : env.entrySet()) {
						int id = entry.getKey();
						RuntimeObject o = entry.getValue();
						/*if (o != null) {
							if (o.getSymbol() == null)
								o.setSymbol(currentPage.getData().get(id));
						}*/
						currentStack.storeClosure(id, o);
					}
					currentStack.pushData(obj);
				}
				int address = func.getAddr();
				currentStack.opCall(address, func.getPage(), currentStack.reg.execId,
						pageName, pageMap.get(func.getPage()).getInfo()
								.getFuncNameByAddress(address));
				currentStack.reg.execId = address;
				currentStack.reg.pageId = func.getPage();
				switchPage();
				pop();
				return;
			} else if (obj.getType() == RuntimeObjectType.kString) {
				name = obj.getObj().toString();
			} else {
				err(RuntimeError.WRONG_LOAD_EXTERN, obj.toString());
			}
		} else {
			RuntimeObject obj = fetchFromGlobalData(idx);
			name = obj.getObj().toString();
		}
		List<RuntimeCodePage> refers = pageRefer.get(pageName);
		for (RuntimeCodePage page : refers) {
			int address = page.getInfo().getAddressOfExportFunc(name);
			if (address != -1) {
				String jmpPage = page.getInfo().getDataMap().get("name")
						.toString();
				currentStack.opCall(address, jmpPage, currentStack.reg.execId,
						currentStack.reg.pageId, name);
				currentStack.reg.execId = address;
				currentStack.reg.pageId = jmpPage;
				switchPage();
				return;
			}
		}
		for (RuntimeCodePage page : refers) {
			IRuntimeDebugExec exec = page.getInfo().getExecCallByName(name);
			if (exec != null) {
				int argsCount = currentStack.getFuncArgsCount();
				RuntimeObjectType[] types = exec.getArgsType();
				if ((types == null && argsCount != 0)
						|| (types != null && types.length != argsCount)) {
					err(RuntimeError.WRONG_ARGCOUNT, name + " " + String.valueOf(argsCount));
				}
				List<RuntimeObject> args = new ArrayList<>();
				for (int i = 0; i < argsCount; i++) {
					RuntimeObjectType type = types == null ? RuntimeObjectType.kObject : (types.length > i ? types[i] : RuntimeObjectType.kObject);
					RuntimeObject objParam = currentStack.loadFuncArgs(i);
					if (type != RuntimeObjectType.kObject) {
						RuntimeObjectType objType = objParam.getType();
						if (objType != type) {
							Token token = Token.createFromObject(objParam
									.getObj());
							TokenType objTokenType = RuntimeObject
									.toTokenType(type);
							if (objTokenType == TokenType.ERROR) {
								err(RuntimeError.WRONG_ARGTYPE, name + " " + objTokenType.getName());
							}
							if (!TokenTools.promote(objTokenType, token)) {
								err(RuntimeError.UNDEFINED_CONVERT, name + " " + token.toString() + " " + objTokenType.getName());
							} else {
								objParam.setObj(token.object);
							}
						}
					}
					args.add(objParam);
				}
				currentStack.opCall(currentStack.reg.execId, currentStack.reg.pageId,
						currentStack.reg.execId, currentStack.reg.pageId, name);
				RuntimeObject retVal;
				try {
					retVal = exec.ExternalProcCall(args, this);
				} catch (RuntimeException e) {
					if (e.getError() == RuntimeError.THROWS_EXCEPTION) {
						opPushObj(new RuntimeObject(e.getInfo()));
						opThrow();
						return;
					}
					throw e;
				}
				if (retVal == null) {
					store(new RuntimeObject(null));
				} else {
					store(retVal);
				}
				opReturn();
				return;
			}
		}
		err(RuntimeError.WRONG_LOAD_EXTERN, name);
	}

	@Override
	public String getHelpString(String name) {
		List<RuntimeCodePage> refers = pageRefer.get(pageName);
		for (RuntimeCodePage page : refers) {
			IRuntimeDebugExec exec = page.getInfo().getExecCallByName(name);
			if (exec != null) {
				String doc = exec.getDoc();
				return doc == null ? "过程无文档" : doc;
			}
		}
		return "过程不存在";
	}

	@Override
	public int getFuncAddr(String name) throws RuntimeException {
		List<RuntimeCodePage> refers = pageRefer.get(pageName);
		for (RuntimeCodePage page : refers) {
			int address = page.getInfo().getAddressOfExportFunc(name);
			if (address != -1) {
				return address;
			}
		}
		err(RuntimeError.WRONG_FUNCNAME, name);
		return -1;
	}

	@Override
	public void opYield(boolean input) throws RuntimeException {
		if (input) {
			enqueue(load());
		} else {
			store(dequeue());
		}
	}

	@Override
	public void opYieldSwitch(boolean forward) throws RuntimeException {
		if (forward) {
			int yldLine = current();
			next();
			String hash = RuntimeTools.getYieldHash(getLastStack(),
					currentStack.getFuncLevel(), pageName, yldLine);
			if (!stkYieldMap.contains(hash)) {
				err(RuntimeError.WRONG_COROUTINE, hash);
			}
			RuntimeStack newStack = stack.get(stkYieldMap.get(hash));
			if (newStack != null) {
				currentStack = newStack;
			} else {
				err(RuntimeError.WRONG_COROUTINE, hash);
			}
		} else {
			if (currentStack.getLevel() == 0) {
				err(RuntimeError.WRONG_COROUTINE);
			}
			if (currentStack.getParent() == -1) {
				err(RuntimeError.WRONG_COROUTINE);
			}
			currentStack = stack.get(currentStack.getParent());
		}
		switchPage();
	}

	private int loadYieldData() throws RuntimeException {
		int size = stkYieldData.size();
		while (!stkYieldData.isEmpty()) {
			opYield(false);
		}
		return size;
	}

	private void loadYieldArgs(int argsSize) throws RuntimeException {
		for (int i = 0; i < argsSize; i++) {
			opPushArgs();
		}
	}

	/**
	 * 向前寻找当前栈的父栈
	 * @return 非yield栈
	 */
	private int getLastStack() {
		int parent = currentStack.getParent();
		if (parent != -1) {
			return parent;
		}
		return currentStack.getLevel();
	}

	private void clearYieldStack() {
		for (int i = 0; i < currentStack.getYield(); i++) {
			stack.remove(stack.size() - 1);
			stkYieldMap.pop();
		}
		currentStack.resetYield();
	}

	@Override
	public void opYieldCreateContext() throws Exception {
		RuntimeStack newStack = new RuntimeStack(stack.size());
		newStack.setParent(currentStack.getLevel());
		int yldLine = current();
		next();
		String hash = RuntimeTools.getYieldHash(currentStack.getLevel(),
				currentStack.getFuncLevel(), pageName, yldLine);
		stkYieldMap.put(hash, newStack.getLevel());
		currentStack.addYield(newStack.getLevel());
		stack.add(newStack);
		refreshStack();
		int yieldSize = loadYieldData();
		int type = loadInt();
		opOpenFunc();
		loadYieldArgs(yieldSize - 2);
		switch (type) {
			case 1:
				opCall();
				break;
			case 2:
				opCallExtern(true);
				break;
			case 3:
				opCallExtern(false);
				break;
			default:
				break;
		}
	}

	@Override
	public void opYieldDestroyContext() {
		int stk = getLastStack();
		stack.remove(stack.size() - 1);
		stkYieldMap.pop();
		currentStack = stack.get(stk);
		currentStack.popYield();
	}

	@Override
	public void opScope(boolean enter) throws RuntimeException {
		if (currentStack.getFuncLevel() == 0)
			err(RuntimeError.EMPTY_CALLSTACK);
		if (enter) {
			currentStack.enterScope();
		} else {
			currentStack.leaveScope();
		}
	}

	@Override
	public void opArr() throws RuntimeException {
		int size = current();
		next();
		RuntimeArray arr = new RuntimeArray();
		for (int i = 0; i < size; i++) {
			arr.add(load());
		}
		currentStack.pushData(new RuntimeObject(arr));
	}

	@Override
	public void opMap() throws RuntimeException {
		int size = current();
		next();
		RuntimeMap map = new RuntimeMap();
		for (int i = 0; i < size; i++) {
			map.put(loadString(), load());
		}
		currentStack.pushData(new RuntimeObject(map));
	}

	@Override
	public void opIndex() throws RuntimeException {
		RuntimeObject index = load();
		RuntimeObject obj = load();
		RuntimeObjectType typeIdx = index.getType();
		RuntimeObjectType typeObj = obj.getType();
		if (typeIdx == RuntimeObjectType.kInt) {
			if (typeObj == RuntimeObjectType.kArray) {
				currentStack.pushData(new RuntimeObject(((RuntimeArray) obj.getObj()).get(((BigInteger) index.getObj()).intValue())));
				return;
			} else if (typeObj == RuntimeObjectType.kString) {
				currentStack.pushData(new RuntimeObject((String.valueOf(obj.getObj()).charAt(((BigInteger) index.getObj()).intValue()))));
				return;
			}
		} else if (typeIdx == RuntimeObjectType.kString) {
			if (typeObj == RuntimeObjectType.kMap) {
				currentStack.pushData(new RuntimeObject(((RuntimeMap) obj.getObj()).get(String.valueOf(index.getObj()))));
				return;
			}
		}
		err(RuntimeError.WRONG_ARGTYPE, obj.toString() + ", " + index.toString());
	}

	@Override
	public void opIndexAssign() throws RuntimeException {
		RuntimeObject index = load();
		RuntimeObject obj = load();
		RuntimeObject exp = load();
		RuntimeObjectType typeIdx = index.getType();
		RuntimeObjectType typeObj = obj.getType();
		if (typeIdx == RuntimeObjectType.kInt) {
			if (typeObj == RuntimeObjectType.kArray) {
				((RuntimeArray) obj.getObj()).set(((BigInteger) index.getObj()).intValue(), exp);
				currentStack.pushData(obj);
				return;
			}
		} else if (typeIdx == RuntimeObjectType.kString) {
			if (typeObj == RuntimeObjectType.kMap) {
				((RuntimeMap) obj.getObj()).put(String.valueOf(index.getObj()), exp);
				currentStack.pushData(obj);
				return;
			}
		}
		err(RuntimeError.WRONG_ARGTYPE, obj.toString() + ", " + index.toString());
	}

	@Override
	public void opTry() throws RuntimeException {
		triesCount++;
		int jmp = current();
		if (jmp != -1 && currentStack.getTry() != -1) {
			err(RuntimeError.DUP_EXCEPTION);
		}
		next();
		currentStack.setTry(jmp);
	}

	@Override
	public void opThrow() throws RuntimeException {
		RuntimeObject obj = load();
		if (triesCount <= 0) {
			if (obj.getType() != RuntimeObjectType.kNull) {
				err(RuntimeError.THROWS_EXCEPTION, String.valueOf(obj.getObj()));
			} else {
				err(RuntimeError.THROWS_EXCEPTION, "NULL");
			}
		}
		if (currentStack.hasNoTry()) {
			while (currentStack.hasNoTry()) {
				opYieldSwitch(false);
				clearYieldStack();
			}
		}
		if (currentStack.hasCatch()) {
			pop();
			opScope(false);
		}
		while (currentStack.getTry() == -1) { // redirect to try stack
			currentStack.opReturn(currentStack.reg); // clear stack
		}
		currentStack.reg.execId = currentStack.getTry();
		store(obj);
		currentStack.resetTry();
		triesCount--;
	}

	public boolean isDebug() {
		return debug;
	}

	public void setDebug(boolean debug) {
		this.debug = debug;
	}
}
