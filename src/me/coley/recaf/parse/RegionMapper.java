package me.coley.recaf.parse;

import java.util.*;
import java.util.Map.Entry;

import org.objectweb.asm.tree.*;

import com.github.javaparser.Range;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.type.*;

import me.coley.recaf.Input;
import me.coley.recaf.Logging;
import me.coley.recaf.bytecode.Asm;

/**
 * Maps ClassNode, FieldNode, and MethodNode to ranges of text by comparing
 * against the AST generated by JavaParser.
 * 
 * For reference:
 * <ul>
 * <li>Quantified name: Full name of a class, such as
 * <i>com.example.MyType</i></li>
 * <li>Simple name: Short-hand name of a class, such as <i>MyType</i></li>
 * </ul>
 * 
 * @author Matt
 */
public class RegionMapper {
	/**
	 * Currently loaded program.
	 */
	private final Input input;
	/**
	 * Class being analyzed.
	 */
	private final ClassNode node;
	/**
	 * The AST of the parsed class.
	 */
	private final CompilationUnit cu;
	/**
	 * Simple name -&gt; List of classes with the simple name, regardless of
	 * package.
	 */
	private final Map<String, Set<ClassNode>> simpleToQuantified = new HashMap<>();
	/**
	 * Quantified name -&gt; class node.
	 */
	private final Map<String, ClassNode> quantifiedToDec = new HashMap<>();
	/**
	 * Class -&gt; set of ranges linked to the class.
	 */
	private final Map<ClassNode, Set<Range>> classRanges = new HashMap<>();
	/**
	 * Member -&gt; set of ranges linked to the member.
	 */
	private final Map<MemberNode, Set<Range>> memberRanges = new HashMap<>();

	public RegionMapper(Input input, ClassNode node, CompilationUnit cu) {
		this.input = input;
		this.node = node;
		this.cu = cu;
	}

	/**
	 * Discover regions and mark their ranges.
	 */
	public void analyze() {
		populateLookups();
		markClassRanges();
		markMemberRanges();
		markOtherRanges();
	}

	/**
	 * Populate simple to quantified lookup maps.
	 */
	private void populateLookups() {
		// add self
		getNameLookup(node.name.substring(node.name.lastIndexOf("/") + 1)).add(node);
		quantifiedToDec.put(node.name, node);
		// read classes from code imports
		List<ImportDeclaration> imports = cu.findAll(ImportDeclaration.class);
		for (ImportDeclaration imp : imports) {
			String name = imp.getNameAsString().replace(".", "/");
			if (input.classes.contains(name)) {
				ClassNode cn = input.getClass(name);
				String cnSimple = cn.name.substring(cn.name.lastIndexOf("/") + 1);
				// add to import lookup
				getNameLookup(cnSimple).add(cn);
				quantifiedToDec.put(cn.name, cn);
				// add range while we're here
				getClassRanges(cn).add(imp.getRange().get());
			} else {
				// Class is not in input.
				// Try to check if it can be generated instead.
				// Knowing as many types as possible is important.
				//
				// TODO: Way of including "java.lang" names without requiring
				// them to be present in the decompiled text's imports.
				try {
					String simple = name.substring(name.lastIndexOf("/") + 1);
					ClassNode dummy = Asm.getNode(Class.forName(name.replace("/", "."), false, ClassLoader
							.getSystemClassLoader()));
					getNameLookup(simple).add(dummy);
					quantifiedToDec.put(simple, dummy);
					// add range while we're here
					getClassRanges(dummy).add(imp.getRange().get());
				} catch (Exception e) {}
			}
		}
		// read classes from input, based on the declared package
		Optional<PackageDeclaration> optPack = cu.findFirst(PackageDeclaration.class);
		if (optPack.isPresent()) {
			// specified package
			String pack = optPack.get().getNameAsString().replace(".", "/");
			input.getClasses().values().forEach(dec -> {
				String name = dec.name;
				int pIndex = name.lastIndexOf("/");
				// The iterated class is in the default package.
				// The analyzed class is not.
				// Skip this iterated class.
				if (pIndex == -1) return;
				String decPackage = name.substring(0, pIndex);
				String decSimple = name.substring(pIndex + 1);
				if (decPackage.equals(pack)) {
					getNameLookup(decSimple).add(dec);
					quantifiedToDec.put(name, dec);
				}
			});
		} else {
			// default package
			input.getClasses().values().forEach(dec -> {
				String name = dec.name;
				if (!name.contains("/")) {
					String decSimple = name.substring(name.lastIndexOf("/") + 1);
					getNameLookup(decSimple).add(dec);
					quantifiedToDec.put(name, dec);
				}
			});
		}
	}

	/**
	 * Mark class ranges.
	 */
	private void markClassRanges() {
		// Add ranges for references
		List<ReferenceType> references = cu.findAll(ReferenceType.class);
		for (ReferenceType clazz : references) {
			if (clazz.getRange().isPresent()) {
				Range range = clazz.getRange().get();
				ClassNode dec = getClass(clazz.asString());
				if (dec != null) {
					getClassRanges(dec).add(range);
				}
			}
		}
		// Add ranges for declarations
		@SuppressWarnings("rawtypes")
		List<TypeDeclaration> declarations = cu.findAll(TypeDeclaration.class);
		for (TypeDeclaration<?> clazz : declarations) {
			SimpleName name = clazz.getName();
			if (name.getRange().isPresent()) {
				Range range = name.getRange().get();
				ClassNode dec = getClass(name.asString());
				if (dec != null) {
					getClassRanges(dec).add(range);
				}
			}
		}
		// Add ranges for constructors
		List<ConstructorDeclaration> constructors = cu.findAll(ConstructorDeclaration.class);
		for (ConstructorDeclaration clazz : constructors) {
			SimpleName name = clazz.getName();
			if (name.getRange().isPresent()) {
				Range range = name.getRange().get();
				ClassNode dec = getClass(name.asString());
				if (dec != null) {
					getClassRanges(dec).add(range);
				}
			}
		}
		// Add ranges for "new MyType()"
		List<ObjectCreationExpr> newOperators = cu.findAll(ObjectCreationExpr.class);
		for (ObjectCreationExpr newExpr : newOperators) {
			if (!newExpr.getScope().isPresent()) {
				// No scope on creation, so it will be "new Type()"
				ClassNode dec = getClass(newExpr.getTypeAsString());
				if (dec != null) {
					getClassRanges(dec).add(newExpr.getType().getRange().get());
				}
			}
		}
	}

	private void markMemberRanges() {
		// Mark declared fields
		List<FieldDeclaration> fields = cu.findAll(FieldDeclaration.class);
		for (FieldDeclaration fd : fields) {
			Optional<Range> nameRange = fd.getVariable(0).getRange();
			if (!nameRange.isPresent()) continue;
			String name = fd.getVariable(0).getNameAsString();
			String desc = getDescriptor(fd.getCommonType());
			Optional<FieldNode> field = node.fields.stream().filter(f -> f.name.equals(name) && f.desc.equals(desc)).findFirst();
			if (field.isPresent()) {
				MemberNode member = new MemberNode(node, field.get());
				getMemberRanges(member).add(nameRange.get());
			}
		}
		// Mark declared methods
		List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
		for (MethodDeclaration md : methods) {
			Optional<Range> nameRange = md.getName().getRange();
			if (!nameRange.isPresent()) continue;
			String name = md.getNameAsString();
			String desc = getMethodDesc(md);
			Optional<MethodNode> method = node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
					.findFirst();
			if (method.isPresent()) {
				MemberNode member = new MemberNode(node, method.get());
				getMemberRanges(member).add(nameRange.get());
			}
		}
		// Mark field references like "this.myField"
		List<FieldAccessExpr> fieldRefs = cu.findAll(FieldAccessExpr.class);
		for (FieldAccessExpr fa : fieldRefs) {
			Optional<Range> nameRange = fa.getName().getRange();
			if (!nameRange.isPresent()) continue;
			String name = fa.getNameAsString();
			Expression scope = fa.getScope();
			if (scope != null && scope.toString().equals("this")) {
				Optional<MemberNode> fdec = getFieldByName(node, name);
				if (fdec.isPresent()) {
					getMemberRanges(fdec.get()).add(nameRange.get());
				}
			} else if (scope != null) {
				Optional<ClassNode> scopeHost = getDecFromScope(scope);
				if (scopeHost.isPresent()) {
					ClassNode cdec = scopeHost.get();
					Optional<MemberNode> mdec = getFieldByName(cdec, name);
					if (mdec.isPresent()) {
						getMemberRanges(mdec.get()).add(nameRange.get());
					}
				}
			}
		}
		// Mark method calls like "this.myMethod()"
		List<MethodCallExpr> methodRefs = cu.findAll(MethodCallExpr.class);
		for (MethodCallExpr mc : methodRefs) {
			Optional<Range> nameRange = mc.getName().getRange();
			if (!nameRange.isPresent()) continue;
			String name = mc.getNameAsString();
			Expression scope = mc.getScope().isPresent() ? mc.getScope().get() : null;
			if (scope != null && scope.toString().equals("this")) {
				Optional<MemberNode> mdec = getMethodByName(node, name, mc.getArguments());
				if (mdec.isPresent()) {
					getMemberRanges(mdec.get()).add(nameRange.get());
				}
			} else if (scope != null) {
				Optional<ClassNode> scopeHost = getDecFromScope(scope);
				if (scopeHost.isPresent()) {
					ClassNode cdec = scopeHost.get();
					Optional<MemberNode> mdec = getMethodByName(cdec, name, mc.getArguments());
					if (mdec.isPresent()) {
						getMemberRanges(mdec.get()).add(nameRange.get());
					}
				}
			}
		}
	}

	private void markOtherRanges() {
		// TODO: range marking - other
		// field assignment: "myField = ..." AssignExpr
		// casts: "(MyClass) object"
		// imports:
		// catch exception type: CatchClause
		//
		// Add ranges for generic name expressions.
		// - Includes class names
		// - Includes field names
		List<NameExpr> names = cu.findAll(NameExpr.class);
		for (NameExpr clazz : names) {
			SimpleName name = clazz.getName();
			// Never event attempt to lookup 'this'
			if (name.toString().equals("this")) {
				continue;
			}
			if (name.getRange().isPresent()) {
				Range range = name.getRange().get();
				ClassNode cdec = getClass(name.asString());
				if (cdec != null) {
					getClassRanges(cdec).add(range);
					continue;
				}
			}
		}
	}

	/**
	 * @param scope
	 *            Some JavaParser expression.
	 * @return The CDec represented by the scope, if one exists.
	 */
	public Optional<ClassNode> getDecFromScope(Expression scope) {
		if (scope.toString().equals("this")) {
			return Optional.of(node);
		}
		if (scope instanceof NameExpr) {
			ClassNode cdec = getClass(scope.toString());
			if (cdec != null) {
				return Optional.of(cdec);
			}
			// Check for variable-references
			Optional<VariableNode> vOpt = getVariableFromContext(scope, scope.toString());
			if (!vOpt.isPresent()) {
				// No variable by name.
				return Optional.empty();
			}
			VariableNode vn = vOpt.get();
			org.objectweb.asm.Type type = org.objectweb.asm.Type.getType(vn.getType());
			String typeStr = type.getInternalName();
			ClassNode varTypeDec = quantifiedToDec.get(typeStr);
			return Optional.ofNullable(varTypeDec);
		} else if (scope instanceof ObjectCreationExpr) {
			// new MyClass() : methodName
			ObjectCreationExpr scopeCreate = (ObjectCreationExpr) scope;
			if (!scopeCreate.getScope().isPresent()) {
				// No scope on creation, so it will be "new Type()"
				return Optional.ofNullable(getClass(scopeCreate.getTypeAsString(), true));
			}
		} else if (scope instanceof FieldAccessExpr) {
			// this.myField : methodName
			// System.out : println
			FieldAccessExpr scopeField = (FieldAccessExpr) scope;
			Optional<ClassNode> context = getDecFromScope(scopeField.getScope());
			if (context.isPresent()) {
				ClassNode contextDec = context.get();
				Optional<MemberNode> memberOpt = getFieldByName(contextDec, scopeField.getNameAsString());
				// Get internal type of the variable, that will be our class
				// declaration to
				// return
				if (memberOpt.isPresent()) {
					MemberNode md = memberOpt.get();
					return Optional.ofNullable(quantifiedToDec.get(md.getInternalType()));
				}
			}
		} else if (scope instanceof MethodCallExpr) {
			// MyClass.methodName() : print
			// myVar.methodName() : print
			// new MyClass().methodName() : print
			MethodCallExpr scopeMethod = (MethodCallExpr) scope;
			if (scopeMethod.getScope().isPresent()) {
				Optional<ClassNode> context = getDecFromScope(scopeMethod.getScope().get());
				if (context.isPresent()) {
					ClassNode contextDec = context.get();
					Optional<MemberNode> memberOpt = getMethodByName(contextDec, scopeMethod.getNameAsString(), scopeMethod
							.getArguments());
					// Get internal/return type of the method, that will be our
					// class declaration to
					// return
					if (memberOpt.isPresent()) {
						MemberNode md = memberOpt.get();
						return Optional.ofNullable(quantifiedToDec.get(md.getInternalType()));
					}
				}
			} else {
				Logging.error("Could not resolve cdec for method-call, no context present: " + scope);
			}
		}
		return Optional.empty();
	}

	/**
	 * @param nodeInMethod
	 *            Node in method that contains the variable.
	 * @param varExpr
	 *            The variable name as an expression.
	 * @return Variable in the method.
	 */
	private Optional<VariableNode> getVariableFromContext(Node nodeInMethod, String varName) {
		Optional<MethodDeclaration> mdOpt = nodeInMethod.findAncestor(MethodDeclaration.class);
		if (mdOpt.isPresent()) {
			MethodDeclaration md = mdOpt.get();
			String mdName = md.getNameAsString();
			String mdDesc = getMethodDesc(md);
			Optional<MethodNode> mOpt = node.methods.stream().filter(m -> m.name.equals(mdName) && m.desc.equals(mdDesc))
					.findFirst();
			if (mOpt.isPresent()) {
				MethodNode method = mOpt.get();
				if (method.localVariables != null) {
					Optional<VariableNode> vOpt = method.localVariables.stream().filter(v -> v.name.equals(varName)).map(
							v -> new VariableNode(v)).findFirst();
					if (vOpt.isPresent()) {
						return vOpt;
					}
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * @param dec
	 *            Host declared class.
	 * @param name
	 *            Name of the field we want to return.
	 * @return Field declaration by the given name.
	 */
	private Optional<MemberNode> getFieldByName(ClassNode dec, String name) {
		if (dec == null || name == null) {
			return Optional.empty();
		}
		ClassNode parent = dec;
		while (parent != null) {
			Optional<MemberNode> opt = parent.fields.stream().filter(m -> {
				return name.equals(m.name);
			}).map(m -> new MemberNode(dec, m)).findFirst();
			if (opt.isPresent()) return opt;
			parent = input.getClass(parent.superName);
		}
		return Optional.empty();
	}

	/**
	 * @param dec
	 *            Host declared class.
	 * @param name
	 *            Name of the method we want to return.
	 * @param args
	 *            Arguments for the method.
	 * @return Method declaration by the given name.
	 */
	private Optional<MemberNode> getMethodByName(ClassNode dec, String name, NodeList<Expression> args) {
		ClassNode parent = dec;
		while (parent != null) {
			Optional<MemberNode> opt = parent.methods.stream().filter(m -> {
				return name.equals(m.name) && argCheck(args, m.desc);
			}).map(m -> new MemberNode(dec, m)).findFirst();
			if (opt.isPresent()) return opt;
			parent = input.getClass(parent.superName);
		}
		return Optional.empty();
	}

	private boolean argCheck(NodeList<Expression> args, String desc) {
		// TODO: To proper type checking of arguments
		// This will combat aggressive overloading
		return args.size() == org.objectweb.asm.Type.getArgumentTypes(desc).length;
	}

	/**
	 * @param cn
	 *            Specified class.
	 * @return Set of all ranges for the specified class.
	 */
	private Set<Range> getClassRanges(ClassNode cn) {
		// Get or create list.
		Set<Range> ranges = classRanges.get(cn);
		if (ranges == null) {
			classRanges.put(cn, ranges = new HashSet<>());
		}
		return ranges;
	}

	/**
	 * @param dec
	 *            Specified member.
	 * @return Set of all ranges for the specified member.
	 */
	private Set<Range> getMemberRanges(MemberNode mn) {
		// Get or create list.
		Set<Range> ranges = memberRanges.get(mn);
		if (ranges == null) {
			memberRanges.put(mn, ranges = new HashSet<>());
		}
		return ranges;
	}

	/**
	 * @param text
	 *            Text representing a class reference.
	 * @return ClassNode from given text.
	 */
	private ClassNode getClass(String text) {
		return getClass(text, true);
	}

	/**
	 * @param text
	 *            Text representing a class reference.
	 * @param silent
	 *            Log class lookup failures.
	 * @return ClassNode from given text.
	 */
	private ClassNode getClass(String text, boolean silent) {
		if (text.contains("<")) {
			text = text.substring(0, text.indexOf("<"));
		}
		if (text.contains("[")) {
			text = text.substring(0, text.indexOf("["));
		}
		Set<ClassNode> set = getNameLookup(text);
		if (set.size() > 1) {
			// more than one match...
			if (!silent) Logging.warn("Multiple simple->quantified for '" + text + "'");
		} else if (set.size() > 0) {
			// only one result
			return set.iterator().next();
		} else {
			// No results
			if (!silent) Logging.warn("Could not find simple->quantified for '" + text + "'");
		}
		return null;
	}

	/**
	 * @param simple
	 *            Simple name of the class.
	 * @return Set of potentially matching class names.
	 */
	private Set<ClassNode> getNameLookup(String simple) {
		if (simple == null) {
			throw new RuntimeException("Requested name lookup, but gave 'null'");
		}
		// Get or create list.
		Set<ClassNode> classes = simpleToQuantified.get(simple);
		if (classes == null) {
			simpleToQuantified.put(simple, classes = new HashSet<>());
		}
		return classes;
	}

	/**
	 * @param type
	 *            JavaParser type.
	 * @return Internal descriptor from type, assuming the type is available
	 *         through {@link #getNameLookup(String)} or if it is a primitive or
	 *         void type.
	 */
	private String getDescriptor(Type type) {
		return isPrim(type) ? primTypeToDesc(type) : typeToDesc(type);
	}

	/**
	 * @param type
	 *            JavaParser type. Must be an object type.
	 * @return Internal descriptor from type, assuming the type is available
	 *         through {@link #getNameLookup(String)}.
	 */
	private String typeToDesc(Type type) {
		String text = type.asString();
		ClassNode dec = getClass(text);
		if (dec == null) {
			return null;
		}
		StringBuilder sbDesc = new StringBuilder();
		for (int i = 0; i < type.getArrayLevel(); i++) {
			sbDesc.append("[");
		}
		sbDesc.append("L");
		sbDesc.append(dec.name);
		sbDesc.append(";");
		return sbDesc.toString();
	}

	/**
	 * @param type
	 *            JavaParser type. Must be a primitive.
	 * @return Internal descriptor.
	 */
	private static String primTypeToDesc(Type type) {
		String desc = null;
		switch (type.asString()) {
		case "boolean":
			desc = "Z";
			break;
		case "int":
			desc = "I";
			break;
		case "long":
			desc = "J";
			break;
		case "short":
			desc = "S";
			break;
		case "byte":
			desc = "B";
			break;
		case "double":
			desc = "D";
			break;
		case "float":
			desc = "F";
			break;
		case "void":
			desc = "V";
			break;
		default:
			throw new RuntimeException("Unknown primitive type field '" + type.asString() + "'");
		}
		StringBuilder sbDesc = new StringBuilder();
		for (int i = 0; i < type.getArrayLevel(); i++) {
			sbDesc.append("[");
		}
		sbDesc.append(desc);
		return sbDesc.toString();
	}

	/**
	 * @param md
	 *            JavaParser method declaration.
	 * @return Internal descriptor from declaration, or {@code null} if any
	 *         parsing failures occured.
	 */
	private String getMethodDesc(MethodDeclaration md) {
		StringBuilder sbDesc = new StringBuilder("(");
		// Append the method parameters for the descriptor
		NodeList<Parameter> params = md.getParameters();
		for (Parameter param : params) {
			Type pType = param.getType();
			String pDesc = getDescriptor(pType);
			if (pDesc == null) {
				return null;
			}
			sbDesc.append(pDesc);
		}
		// Append the return type for the descriptor
		Type typeRet = md.getType();
		String retDesc = getDescriptor(typeRet);
		if (retDesc == null) {
			return null;
		}
		sbDesc.append(")");
		sbDesc.append(retDesc);
		return sbDesc.toString();
	}

	/**
	 * @param type
	 *            JavaParser type.
	 * @return {@code true} if the type denotes a primitive or void type.
	 */
	private static boolean isPrim(Type type) {
		// void is not a primitive, but lets just pretend it is.
		return type.isVoidType() || type.isPrimitiveType();
	}

	// ============================================================================

	/**
	 * @param line
	 *            Caret line in editor.
	 * @param column
	 *            Caret column in editor.
	 * @return CDec at position. May be {@code null}.
	 */
	public ClassNode getClassFromPosition(int line, int column) {
		for (Entry<ClassNode, Set<Range>> e : classRanges.entrySet()) {
			for (Range range : e.getValue()) {
				if (isInRange(range, line, column)) {
					return e.getKey();
				}
			}
		}
		return null;
	}

	/**
	 * @param line
	 *            Caret line in editor.
	 * @param column
	 *            Caret column in editor.
	 * @return MDec at position. May be {@code null}.
	 */
	public MemberNode getMemberFromPosition(int line, int column) {
		for (Entry<MemberNode, Set<Range>> e : memberRanges.entrySet()) {
			for (Range range : e.getValue()) {
				if (isInRange(range, line, column)) {
					return e.getKey();
				}
			}
		}
		return null;
	}

	/**
	 * @param range
	 *            Range to check bounds.
	 * @param line
	 *            Caret line in editor.
	 * @param column
	 *            Caret column in editor.
	 * @return {@code true} if caret position is within the range.
	 */
	private boolean isInRange(Range range, int line, int column) {
		if (range.begin.line != range.end.line) throw new RuntimeException("Invalid range: " + range);
		return line == range.begin.line && column >= range.begin.column && column <= (range.end.column + 1);
	}
}
