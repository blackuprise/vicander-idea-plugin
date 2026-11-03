package io.vicander.idea;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.psi.util.PsiTreeUtil;
import org.elixir_lang.psi.NamedElement;
import org.elixir_lang.psi.call.Call;
import org.elixir_lang.psi.stub.index.ModularName;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Module lookup shared by the go-to-declaration handlers. */
final class ModuleLookup {
  static final String DEFMODULE = "defmodule";
  private static final int MAX_ALIAS_HOPS = 4;

  private ModuleLookup() {}

  /**
   * Resolves an alias such as {@code TodoList} to its {@code defmodule}, following {@code alias}
   * declarations a few hops when the first resolution lands on one.
   */
  static void collectModules(PsiElement element, Set<Call> into, int hops) {
    if (hops > MAX_ALIAS_HOPS) {
      return;
    }
    for (PsiElement resolved : resolve(element)) {
      if (resolved instanceof Call && DEFMODULE.equals(((Call) resolved).functionName())) {
        into.add((Call) resolved);
      } else if (resolved instanceof NamedElement && !resolved.equals(element)) {
        // Landed on an `alias` (or similar): resolve again from there.
        collectModules(resolved, into, hops + 1);
      }
    }
  }

  static List<PsiElement> resolve(PsiElement element) {
    List<PsiElement> out = new ArrayList<>();
    for (PsiReference reference : element.getReferences()) {
      if (reference instanceof PsiPolyVariantReference) {
        for (ResolveResult result : ((PsiPolyVariantReference) reference).multiResolve(false)) {
          if (result.getElement() != null) {
            out.add(result.getElement());
          }
        }
      } else {
        PsiElement resolved = reference.resolve();
        if (resolved != null) {
          out.add(resolved);
        }
      }
    }
    return out;
  }

  static void addByName(@Nullable String moduleName, Project project, Set<Call> into) {
    if (moduleName == null || DumbService.isDumb(project)) {
      return;
    }
    Collection<NamedElement> found =
        StubIndex.getElements(
            ModularName.KEY, moduleName, project, GlobalSearchScope.projectScope(project),
            NamedElement.class);
    for (NamedElement module : found) {
      if (module instanceof Call) {
        into.add((Call) module);
      }
    }
  }

  /**
   * Drops modules outside the project (SDK and dependency sources such as {@code Enum}): they
   * never hold the declarations we look for, and scanning them is slow.
   */
  static void retainProjectModules(Set<Call> modules, Project project) {
    ProjectFileIndex index = ProjectFileIndex.getInstance(project);
    for (Iterator<Call> it = modules.iterator(); it.hasNext(); ) {
      VirtualFile file = it.next().getContainingFile().getVirtualFile();
      if (file == null || !index.isInContent(file)) {
        it.remove();
      }
    }
  }

  /**
   * The calls inside {@code module} that belong to it, i.e. not to a {@code defmodule} nested in
   * it.
   */
  static List<Call> ownCalls(Call module) {
    List<Call> out = new ArrayList<>();
    for (Call call : PsiTreeUtil.findChildrenOfType(module, Call.class)) {
      if (enclosingModule(call) == module) {
        out.add(call);
      }
    }
    return out;
  }

  static @Nullable Call enclosingModule(PsiElement from) {
    return enclosingCall(from, Set.of(DEFMODULE));
  }

  /** The nearest strictly enclosing call whose function name is in {@code names}. */
  static @Nullable Call enclosingCall(PsiElement from, Set<String> names) {
    Call current = PsiTreeUtil.getParentOfType(from, Call.class, true);
    while (current != null) {
      String name = current.functionName();
      if (name != null && names.contains(name)) {
        return current;
      }
      current = PsiTreeUtil.getParentOfType(current, Call.class, true);
    }
    return null;
  }
}
