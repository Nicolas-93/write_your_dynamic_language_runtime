package fr.umlv.smalljs.astinterp;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.Call;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.Identifier;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.ObjectLiteral;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Expr.VarAssignment;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static fr.umlv.smalljs.ast.ASTBuilder.createScript;
import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.joining;

public final class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  private static Object execute(Expr.Block body, JSObject env) {
    // initialize declared variables to UNDEFINED
    visitVariable(body, env);
    // interpret the AST
    return visit(body, env);
  }

  private static void visitVariable(Expr expression, JSObject env) {
    switch (expression) {
      case Block(List<Expr> exprs, _) -> {
        for (var expr : exprs) {
          visitVariable(expr, env);
        }
      }
      case VarAssignment(String name, _, boolean declaration, _) -> {
        if (declaration) {
          env.register(name, UNDEFINED);
        }
      }
      case If(_, Block trueBlock, Block falseBlock, _) -> {
        visitVariable(trueBlock, env);
        visitVariable(falseBlock, env);
      }
      case Literal _, Call _, Identifier _, Fun _, Return _, ObjectLiteral _, FieldAccess _,
           FieldAssignment _, MethodCall _ -> {
        // do nothing
      }
    };
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {
      case Block(List<Expr> exprs, int lineNumber) -> {
        exprs.forEach(expr -> visit(expr, env));
        yield UNDEFINED;
      }
      case Literal(Object value, int lineNumber) -> {
        yield value;
      }
      case Call(Expr qualifier, List<Expr> args, int lineNumber) -> {
        var maybeFunction = visit(qualifier, env);
        if (maybeFunction instanceof JSObject function) {
          var evaluated = args.stream().map(expr -> visit(expr, env)).toArray();
          yield function.invoke(UNDEFINED, evaluated);
        } else {
          throw new Failure(maybeFunction + " is not a function at line " + lineNumber);
        }
      }
      case Identifier(String name, int lineNumber) -> {
        var value = env.lookupOrDefault(name, null);
        if (value == null) {
          throw new Failure("No such variable name for " + value + " at line " + lineNumber);
        }
        yield value;
      }
      case VarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
        var variable = env.lookupOrDefault(name, null);
        if (!declaration && variable == null) {
          throw new Failure("not a declaration " + name + "at line" + lineNumber);
        }
        var value = visit(expr, env);
        env.register(name, value);
        yield value;
      }
      case Fun(String name, List<String> parameters, boolean toplevel, Block body, int lineNumber) -> {
        JSObject.Invoker invoker = new JSObject.Invoker() {
          @Override
          public Object invoke(Object receiver, Object... args) {
            if (parameters.size() != args.length) {
              throw new Failure("wrong number of arguments for " + name + " at line number " + lineNumber);
            }
            var newEnv = JSObject.newEnv(env);
            newEnv.register("this", receiver);

            for (int i = 0; i < parameters.size(); i++) {
              newEnv.register(parameters.get(i), args[i]);
            }

            try {
              execute(body, newEnv);
            } catch (ReturnError e) {
              return e.getValue();
            }

            return UNDEFINED;
          }
        };

        // create the JS function with the invoker
        var function = JSObject.newFunction(name, invoker);
        // register it into the global env if it's a toplevel
        if (toplevel) {
          env.register(name, function);
        }
        yield function;
      }
      case Return(Expr expr, int lineNumber) -> {
        var res = visit(expr, env);
        throw new ReturnError(res);
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        final var computed = visit(condition, env);
        boolean trueValue;
        if (computed instanceof Integer value) {
          trueValue = value != 0;
        }
        else {
          trueValue = computed != UNDEFINED;
        }
        if (trueValue) {
          yield visit(trueBlock, env);
        }
        else {
          yield visit(falseBlock, env);
        }
      }
      case ObjectLiteral(Map<String, Expr> initMap, int lineNumber) -> {
        var object = JSObject.newObject(null);
        initMap.forEach((s, expr) -> object.register(s, visit(expr, env)));
        yield object;
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        var object = visit(receiver, env);
        if (object instanceof JSObject jsObject) {
          yield jsObject.lookupOrDefault(name, UNDEFINED);
        }
        yield UNDEFINED;
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        var object = visit(receiver, env);
        if (object instanceof JSObject jsObject) {
          jsObject.register(name, visit(expr, env));
        }
        yield UNDEFINED;
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        var object = visit(receiver, env);
        if (object instanceof JSObject jsObject) {
          var maybeFunction = jsObject.lookupOrDefault(name, UNDEFINED);
          if (maybeFunction instanceof JSObject function) {
            var evaluated = args.stream().map(expr -> visit(expr, env)).toArray();
            yield function.invoke(jsObject, evaluated);
          } else {
            throw new Failure(maybeFunction + " is not a function at line " + lineNumber);
          }
        }
        yield null;
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static JSObject createGlobalEnv(PrintStream outStream) {
    var globalEnv = JSObject.newEnv(null);
    globalEnv.register("globalThis", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (_, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (_, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (_, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (_, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (_, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (_, args) -> (Integer) args[0] % (Integer) args[1]));
    globalEnv.register("==", JSObject.newFunction("==", (_, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (_, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    return globalEnv;
  }

  public static void interpret(Script script, PrintStream outStream) {
    var globalEnv = createGlobalEnv(outStream);
    var body = script.body();
    execute(body, globalEnv);
  }

  static void main() {
    var script = createScript("""
      var f = 1
      f()
      """
    );
    var outStream = new ByteArrayOutputStream(8192);
    ASTInterpreter.interpret(script, new PrintStream(outStream, false, UTF_8));
    System.out.println(outStream.toString(UTF_8).replace("\r\n", "\n"));

  }
}

