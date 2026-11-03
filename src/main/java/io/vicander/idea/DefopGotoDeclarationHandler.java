package io.vicander.idea;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.psi.qualification.Qualified;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ctrl+click on {@code TodoList.complete_item(...)} goes to the {@code defop complete_item(...)}
 * that defines it, with no generated stub needed.
 *
 * <p>{@code defop} defines the public function only when the module finishes compiling, so the
 * Elixir plugin cannot resolve the call itself, and anything derived from the call's own
 * resolution ({@code resolvedModuleName()}) is null. The module is therefore found from the
 * call's <em>qualifier</em> ({@code TodoList}, resolved through {@code alias}), which does
 * resolve. This handler runs before the default resolution; when it returns targets they win,
 * otherwise the default resolution is untouched.
 */
public final class DefopGotoDeclarationHandler implements GotoDeclarationHandler {
  private static final String DEFOP = "defop";

  /** Declarations whose first argument is a head, not a call to navigate from. */
  static final Set<String> DEFINITIONS =
      Set.of("def", "defp", "defmacro", "defmacrop", "defop", "defguard", "defguardp");

  /** Special forms and Kernel macros that are never a defop call; skipped without any lookup. */
  private static final Set<String> NEVER_DEFOP =
      Set.of(
          "def", "defp", "defmacro", "defmacrop", "defguard", "defguardp", "defmodule", "defstruct",
          "defdelegate", "defimpl", "defprotocol", "defexception", "defoverridable", "defop",
          "if", "unless", "case", "cond", "with", "for", "try", "receive", "quote", "unquote",
          "fn", "use", "import", "alias", "require", "raise", "throw", "send", "spawn", "field",
          "identity", "find_by");

  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(
      @Nullable PsiElement sourceElement, int offset, Editor editor) {
    if (sourceElement == null) {
      return null;
    }

    Call call = PsiTreeUtil.getParentOfType(sourceElement, Call.class, false);
    if (call == null || call.functionNameElement() == null) {
      return null;
    }
    // Only when the identifier under the caret is the function name of the call.
    if (!PsiTreeUtil.isAncestor(call.functionNameElement(), sourceElement, false)) {
      return null;
    }

    String functionName = call.functionName();
    if (functionName == null || NEVER_DEFOP.contains(functionName)) {
      return null;
    }
    // The name in `defop name(...)` itself: leave it to the IDE (usages), don't jump to self.
    if (isDeclarationHead(call)) {
      return null;
    }

    int arity = call.resolvedFinalArity();
    Project project = sourceElement.getProject();

    Set<Call> modules = new LinkedHashSet<>();
    if (call instanceof Qualified) {
      PsiElement qualifier = ((Qualified) call).qualifier();
      if (qualifier != null) {
        ModuleLookup.collectModules(qualifier, modules, 0);
      }
    }
    // Unqualified calls (`import`ed or inside the module itself) and anything the qualifier
    // could not settle: fall back to the names the Elixir plugin does know.
    if (modules.isEmpty()) {
      ModuleLookup.addByName(call.resolvedModuleName(), project, modules);
    }
    if (modules.isEmpty()) {
      ModuleLookup.addByName(call.moduleName(), project, modules);
    }
    ModuleLookup.retainProjectModules(modules, project);

    List<PsiElement> targets = new ArrayList<>();
    for (Call module : modules) {
      for (Call defop : ModuleLookup.ownCalls(module)) {
        if (DEFOP.equals(defop.functionName()) && defines(defop, functionName, arity)) {
          PsiElement target = defop.functionNameElement();
          targets.add(target != null ? target : defop);
        }
      }
    }

    return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
  }

  /** True when {@code call} is the head being declared by an enclosing {@code def}/{@code defop}. */
  private static boolean isDeclarationHead(Call call) {
    Call outer = ModuleLookup.enclosingCall(call, DEFINITIONS);
    if (outer == null) {
      return false;
    }
    PsiElement[] arguments = outer.primaryArguments();
    return arguments != null
        && arguments.length > 0
        && PsiTreeUtil.isAncestor(arguments[0], call, false);
  }

  /**
   * True when {@code defop name(args...)} declares {@code name/arity}: also with a {@code when}
   * guard, with default arguments ({@code opts \\ []} declares every arity down to the first
   * default), and with no parentheses ({@code defop name do} is {@code name/0}).
   */
  private static boolean defines(Call defop, String name, int arity) {
    PsiElement[] arguments = defop.primaryArguments();
    if (arguments == null || arguments.length == 0) {
      return false;
    }
    PsiElement head = arguments[0];

    // `defop name(a, b) when guard` is a `when` operation around the call. The Elixir plugin may
    // model that operation as a Call itself (function name "when"), so look for the declared call
    // by name rather than assuming the first argument is it. The head is the first match in
    // document order, i.e. left of `when`.
    Call declared = null;
    if (head instanceof Call && name.equals(((Call) head).functionName())) {
      declared = (Call) head;
    } else {
      for (Call candidate : PsiTreeUtil.findChildrenOfType(head, Call.class)) {
        if (name.equals(candidate.functionName())) {
          declared = candidate;
          break;
        }
      }
    }
    if (declared == null) {
      // A bare `defop name do` / `defop name when ...` that the PSI does not model as a call.
      String text = head.getText().trim();
      return arity == 0
          && (text.equals(name) || text.matches(Pattern.quote(name) + "\\s+when\\b[\\s\\S]*"));
    }

    PsiElement[] params = declared.primaryArguments();
    List<String> texts = new ArrayList<>();
    if (params != null) {
      for (PsiElement param : params) {
        texts.add(param.getText());
      }
    }
    return acceptsArity(texts, arity);
  }

  /** True when a head with these parameter texts can be called with {@code arity} arguments. */
  static boolean acceptsArity(List<String> parameters, int arity) {
    int defaults = 0;
    for (String parameter : parameters) {
      if (parameter.contains("\\\\")) {
        defaults++;
      }
    }
    return arity >= parameters.size() - defaults && arity <= parameters.size();
  }

  @Override
  public @Nullable String getActionText(@NotNull DataContext context) {
    return null;
  }
}
