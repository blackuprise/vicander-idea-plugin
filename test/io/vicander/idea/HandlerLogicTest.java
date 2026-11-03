package io.vicander.idea;

import java.util.List;

/**
 * Dependency-free tests for the pure logic behind the handlers (no JUnit is available to the
 * Gradle-less build). Run with {@code ./test.sh}; exits non-zero on any failure.
 *
 * <p>The PSI-driven navigation itself needs a running IDE and the Elixir plugin and is not
 * covered here.
 */
public final class HandlerLogicTest {
  private static int failures;
  private static int total;

  public static void main(String[] args) {
    structOf();
    rebinds();
    acceptsArity();
    finderName();
    portText();

    System.out.println(total - failures + "/" + total + " passed");
    System.exit(failures == 0 ? 0 : 1);
  }

  // --- FieldGotoDeclarationHandler.structOf -------------------------------------------------

  private static void structOf() {
    struct("execute(%__MODULE__{} = command)", "command", "__MODULE__");
    struct("execute(%Cmd{} = command)", "command", "Cmd");
    struct("execute(%My.App.Cmd{} = command)", "command", "My.App.Cmd");
    struct("execute(command = %Cmd{})", "command", "Cmd");
    struct("execute(%Cmd{a: 1, b: 2} = command)", "command", "Cmd");
    // Nested maps and structs in the pattern.
    struct("execute(%__MODULE__{meta: %{} = m} = command)", "command", "__MODULE__");
    struct("execute(%Cmd{tags: %{a: 1}} = command)", "command", "Cmd");
    struct("execute(%Cmd{inner: %Other{x: %{}}} = command)", "command", "Cmd");
    struct("execute(command = %Cmd{a: %{b: %{}}})", "command", "Cmd");
    // The nested binding is found for its own variable.
    struct("execute(%Cmd{inner: %Other{} = o} = command)", "o", "Other");
    // Several parameters.
    struct("execute(%Cmd{} = command, %Other{} = list)", "list", "Other");
    struct("execute(%Cmd{} = command, %Other{} = list)", "command", "Cmd");
    // Braces inside strings do not confuse the matcher.
    struct("execute(%A{b: \"}\"} = c)", "c", "A");
    struct("execute(%A{b: \"{\"} = c)", "c", "A");
    // Not a match.
    struct("execute(%Cmd{} = command2)", "command", null);
    struct("execute(%Cmd{} = command)", "comm", null);
    struct("execute(x, %B{} = y)", "x", null);
    struct("f(a == %A{})", "a", null);
    struct("f(%A{} == a)", "a", null);
    struct("execute(command)", "command", null);
    struct("execute(%Unclosed{ = command)", "command", null);
  }

  private static void struct(String head, String variable, String expected) {
    check("structOf(" + head + ", " + variable + ")",
        expected, FieldGotoDeclarationHandler.structOf(head, variable));
  }

  // --- FieldGotoDeclarationHandler.rebinds --------------------------------------------------

  private static void rebinds() {
    rebind("command = %Other{}", "command", true);
    rebind("x = 1\n  command = update(command)", "command", true);
    rebind("command=1", "command", true);
    // Comparisons, matches against other things, and look-alikes are not rebinding.
    rebind("command == other", "command", false);
    rebind("command =~ ~r/x/", "command", false);
    rebind("command => 1", "command", false);
    rebind("my_command = 1", "command", false);
    rebind("socket.command = 1", "command", false);
    rebind("@command = 1", "command", false);
    rebind("command2 = 1", "command", false);
    rebind("IO.inspect(command)", "command", false);
    rebind("", "command", false);
  }

  private static void rebind(String code, String variable, boolean expected) {
    check("rebinds(" + code.replace("\n", "\\n") + ", " + variable + ")",
        expected, FieldGotoDeclarationHandler.rebinds(code, variable));
  }

  // --- DefopGotoDeclarationHandler.acceptsArity ---------------------------------------------

  private static void acceptsArity() {
    arity(List.of(), 0, true);
    arity(List.of(), 1, false);
    arity(List.of("a", "b"), 2, true);
    arity(List.of("a", "b"), 1, false);
    arity(List.of("a", "b"), 3, false);
    // Default arguments declare every arity down to the first default.
    arity(List.of("list", "id", "opts \\\\ []"), 3, true);
    arity(List.of("list", "id", "opts \\\\ []"), 2, true);
    arity(List.of("list", "id", "opts \\\\ []"), 1, false);
    arity(List.of("a \\\\ 1", "b \\\\ 2"), 0, true);
    arity(List.of("a \\\\ 1", "b \\\\ 2"), 1, true);
    arity(List.of("a \\\\ 1", "b \\\\ 2"), 2, true);
    arity(List.of("a \\\\ 1", "b \\\\ 2"), 3, false);
  }

  private static void arity(List<String> parameters, int arity, boolean expected) {
    check("acceptsArity(" + parameters + ", " + arity + ")",
        expected, DefopGotoDeclarationHandler.acceptsArity(parameters, arity));
  }

  // --- RepositoryGotoDeclarationHandler.finderName ------------------------------------------

  private static void finderName() {
    finder(":list_id", "find_by_list_id");
    finder("[:a, :b]", "find_by_a_and_b");
    finder("[ :a ,  :b ,:c ]", "find_by_a_and_b_and_c");
    finder("  :list_id  ", "find_by_list_id");
    // Keyword form and quoted atoms.
    finder("list_id:", "find_by_list_id");
    finder(":\"list_id\"", "find_by_list_id");
    finder("[:\"a\", :b]", "find_by_a_and_b");
    // Nothing usable.
    finder("", null);
    finder("[]", null);
    finder("[:a, ]", null);
  }

  private static void finder(String argument, String expected) {
    check("finderName(" + argument + ")",
        expected, RepositoryGotoDeclarationHandler.finderName(argument));
  }

  // --- RepositoryGotoDeclarationHandler.isPortText ------------------------------------------

  private static void portText() {
    port("DDD.Repository", true);
    port("  DDD.Repository  ", true);
    port("DDD.Repository, unique: true", true);
    port("DDD.Repository,\n  opts", true);
    port("DDD.Repository.Ecto", false);
    port("DDD.RepositoryX", false);
    port("Repository", false);
    port("", false);
  }

  private static void port(String text, boolean expected) {
    check("isPortText(" + text.replace("\n", "\\n") + ")",
        expected, RepositoryGotoDeclarationHandler.isPortText(text));
  }

  // --- harness ------------------------------------------------------------------------------

  private static void check(String what, Object expected, Object actual) {
    total++;
    boolean ok = expected == null ? actual == null : expected.equals(actual);
    if (!ok) {
      failures++;
      System.out.println("FAIL " + what + ": expected " + expected + " but got " + actual);
    }
  }
}
