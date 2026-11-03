package io.vicander.idea;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ctrl+click on {@code command.list_id} goes to the {@code field :list_id, ...} that declares it.
 *
 * <p>The struct behind {@code command do ... end} / {@code entity do ... end} is built by a macro,
 * so the Elixir plugin has no declaration to resolve a field access to. The struct module is read
 * from the enclosing function's head ({@code def execute(%__MODULE__{} = command)}, or
 * {@code %Cmd{} = command}), and the declaration is the {@code field}/{@code identity} call of
 * that module naming the field.
 */
public final class FieldGotoDeclarationHandler implements GotoDeclarationHandler {
  private static final Set<String> DECLARATIONS = Set.of("field", "identity");
  private static final Pattern VARIABLE = Pattern.compile("[a-z_][A-Za-z0-9_]*");
  private static final Pattern STRUCT_START = Pattern.compile("%\\s*([A-Za-z_][\\w.]*)\\s*\\{");

  @Override
  public PsiElement @Nullable [] getGotoDeclarationTargets(
      @Nullable PsiElement sourceElement, int offset, Editor editor) {
    if (sourceElement == null) {
      return null;
    }

    Call access = PsiTreeUtil.getParentOfType(sourceElement, Call.class, false);
    if (!(access instanceof Qualified) || access.functionNameElement() == null) {
      return null;
    }
    if (!PsiTreeUtil.isAncestor(access.functionNameElement(), sourceElement, false)) {
      return null;
    }
    String field = access.functionName();
    PsiElement qualifier = ((Qualified) access).qualifier();
    if (field == null || qualifier == null) {
      return null;
    }
    String variable = qualifier.getText();
    if (!VARIABLE.matcher(variable).matches()) {
      return null;
    }

    Call function = ModuleLookup.enclosingCall(access, DefopGotoDeclarationHandler.DEFINITIONS);
    if (function == null) {
      return null;
    }
    PsiElement[] arguments = function.primaryArguments();
    if (arguments == null || arguments.length == 0) {
      return null;
    }
    PsiElement head = arguments[0];

    // Cheap exit: a struct pattern in the head is the only thing this handler understands.
    String headText = head.getText();
    if (headText.indexOf('%') < 0) {
      return null;
    }
    String structName = structOf(headText, variable);
    if (structName == null) {
      return null;
    }
    if (rebindsBetween(function, head, access, variable)) {
      return null;
    }

    Set<Call> modules = new LinkedHashSet<>();
    if ("__MODULE__".equals(structName)) {
      Call module = ModuleLookup.enclosingModule(function);
      if (module != null) {
        modules.add(module);
      }
    } else {
      PsiElement alias = findAlias(head, structName);
      if (alias != null) {
        ModuleLookup.collectModules(alias, modules, 0);
      }
      if (modules.isEmpty()) {
        ModuleLookup.addByName(structName, function.getProject(), modules);
      }
    }
    ModuleLookup.retainProjectModules(modules, function.getProject());

    String atom = ":" + field;
    List<PsiElement> targets = new ArrayList<>();
    for (Call module : modules) {
      for (Call declaration : ModuleLookup.ownCalls(module)) {
        String declName = declaration.functionName();
        if (declName == null || !DECLARATIONS.contains(declName)) {
          continue;
        }
        PsiElement[] declared = declaration.primaryArguments();
        if (declared != null && declared.length > 0 && atom.equals(declared[0].getText())) {
          targets.add(declared[0]);
        }
      }
    }
    return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
  }

  /**
   * The struct name bound to `variable` in a function head: `%Name{...} = var` or
   * `var = %Name{...}`. The braces are matched by depth, so nested maps and structs in the pattern
   * (`%Cmd{meta: %{} = m} = command`) are fine.
   */
  static @Nullable String structOf(String head, String variable) {
    String var = Pattern.quote(variable);
    Pattern afterClose = Pattern.compile("\\s*=(?!=)\\s*" + var + "(?![\\w?!])");
    Pattern beforeOpen = Pattern.compile("(?<![\\w])" + var + "\\s*=(?!=)\\s*$");

    Matcher start = STRUCT_START.matcher(head);
    int from = 0;
    while (start.find(from)) {
      from = start.start() + 1;
      int close = matchingBrace(head, start.end() - 1);
      if (close < 0) {
        continue;
      }
      // `%Name{...} = var`
      Matcher tail = afterClose.matcher(head).region(close + 1, head.length());
      if (tail.lookingAt()) {
        return start.group(1);
      }
      // `var = %Name{...}`
      if (beforeOpen.matcher(head.substring(0, start.start())).find()) {
        return start.group(1);
      }
    }
    return null;
  }

  /** Index of the `}` matching the `{` at {@code open}, skipping string literals; -1 if none. */
  private static int matchingBrace(String text, int open) {
    int depth = 0;
    for (int i = open; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        i++;
        while (i < text.length() && text.charAt(i) != '"') {
          if (text.charAt(i) == '\\') {
            i++;
          }
          i++;
        }
      } else if (c == '{') {
        depth++;
      } else if (c == '}' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }

  /**
   * True when the body rebinds {@code variable} (`command = ...`) between the head and the access,
   * in which case the head's struct no longer describes it.
   */
  private static boolean rebindsBetween(
      Call function, PsiElement head, PsiElement access, String variable) {
    int functionStart = function.getTextRange().getStartOffset();
    int from = head.getTextRange().getEndOffset() - functionStart;
    int to = access.getTextRange().getStartOffset() - functionStart;
    if (from >= to) {
      return false;
    }
    return rebinds(function.getText().substring(from, to), variable);
  }

  /** True when {@code code} contains an assignment to {@code variable}. */
  static boolean rebinds(String code, String variable) {
    return Pattern.compile("(?<![\\w.:@])" + Pattern.quote(variable) + "\\s*=(?![=~>])")
        .matcher(code)
        .find();
  }

  /**
   * The alias element for `name` in struct position (`%Name{`) inside the head, so the Elixir
   * plugin can resolve it. The innermost matching element wins, not a wrapper around it.
   */
  private static @Nullable PsiElement findAlias(PsiElement head, String name) {
    String headText = head.getText();
    int headStart = head.getTextRange().getStartOffset();
    for (PsiElement child : PsiTreeUtil.findChildrenOfType(head, PsiElement.class)) {
      if (!name.equals(child.getText()) || child.getReferences().length == 0) {
        continue;
      }
      int at = child.getTextRange().getStartOffset() - headStart;
      if (at < 0 || !Pattern.compile("%\\s*$").matcher(headText.substring(0, at)).find()) {
        continue;
      }
      boolean hasInner = false;
      for (PsiElement inner : PsiTreeUtil.findChildrenOfType(child, PsiElement.class)) {
        if (name.equals(inner.getText()) && inner.getReferences().length > 0) {
          hasInner = true;
          break;
        }
      }
      if (!hasInner) {
        return child;
      }
    }
    return null;
  }

  @Override
  public @Nullable String getActionText(@NotNull DataContext context) {
    return null;
  }
}
