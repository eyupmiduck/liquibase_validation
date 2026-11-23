package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RoutineBodies}: it extracts {@code $$} and {@code $tag$} bodies,
 * preserves absolute line/column positions, handles multiple routines, ignores
 * the declaration header, and falls back to a bare body.
 */
class RoutineBodiesTest {

    private static final Path FILE = Path.of("/db/function.sql");

    private static SqlUnit unit(String sql) {
        SqlSource source = new SqlSource(SqlSource.Kind.ROUTINE_BODY, FILE, null, true, ";", false, null);
        return new SqlUnit(source, FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }

    private static Token keyword(RoutineBodies.Body body, String keyword) {
        return body.tokens().stream()
                .filter(token -> !token.isTrivia() && token.matchesKeyword(keyword))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + keyword + " in " + body.name()));
    }

    @Test
    void extractsADollarQuotedBodyWithAbsolutePositions() {
        List<RoutineBodies.Body> bodies = RoutineBodies.find(List.of(unit("""
                CREATE FUNCTION app.f(i_x integer) RETURNS void AS $$
                BEGIN
                  EXECUTE 'x';
                END;
                $$ LANGUAGE plpgsql;
                """)));

        assertEquals(1, bodies.size());
        RoutineBodies.Body body = bodies.getFirst();
        assertEquals("app.f", body.name());
        assertEquals(RoutineBodies.Kind.FUNCTION, body.kind());
        Token execute = keyword(body, "EXECUTE");
        assertEquals(3, execute.line());
        assertEquals(3, execute.column());
    }

    @Test
    void extractsATaggedBodyAndKeepsAnInnerDollarString() {
        List<RoutineBodies.Body> bodies = RoutineBodies.find(List.of(unit("""
                CREATE FUNCTION app.f() RETURNS void AS $body$
                BEGIN
                  PERFORM $$inner$$;
                  EXECUTE 'x';
                END;
                $body$ LANGUAGE plpgsql;
                """)));

        assertEquals(1, bodies.size());
        RoutineBodies.Body body = bodies.getFirst();
        assertTrue(body.tokens().stream().anyMatch(token -> token.type()
                == io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType.DOLLAR_STRING
                && token.text().equals("$$inner$$")));
        Token execute = keyword(body, "EXECUTE");
        assertEquals(4, execute.line());
        assertEquals(3, execute.column());
    }

    @Test
    void keepsBodyTokensOutOfCommentsAndStrings() {
        RoutineBodies.Body body = RoutineBodies.find(List.of(unit("""
                CREATE FUNCTION f() RETURNS void AS $$
                BEGIN
                  -- EXECUTE is a comment
                  PERFORM 'EXECUTE is a string';
                  EXECUTE 'real';
                END;
                $$ LANGUAGE plpgsql;
                """))).getFirst();

        assertTrue(body.tokens().stream().anyMatch(token -> token.type().isTrivia()));
        assertEquals(1, body.tokens().stream()
                .filter(token -> !token.isTrivia() && token.matchesKeyword("EXECUTE"))
                .count());
        assertEquals(5, keyword(body, "EXECUTE").line());
    }

    @Test
    void extractsEveryRoutineAndTheProcedureKind() {
        List<RoutineBodies.Body> bodies = RoutineBodies.find(List.of(unit(
                "CREATE FUNCTION a() RETURNS int AS $$ SELECT 1; $$ LANGUAGE sql;"
                        + " CREATE PROCEDURE b() LANGUAGE plpgsql AS $$ BEGIN NULL; END; $$;")));

        assertEquals(2, bodies.size());
        assertEquals("a", bodies.get(0).name());
        assertEquals(RoutineBodies.Kind.FUNCTION, bodies.get(0).kind());
        assertEquals("b", bodies.get(1).name());
        assertEquals(RoutineBodies.Kind.PROCEDURE, bodies.get(1).kind());
    }

    @Test
    void preservesThePositionOfABodyOnTheDeclarationLine() {
        RoutineBodies.Body body = RoutineBodies.find(List.of(unit(
                "CREATE FUNCTION f() RETURNS void AS $$ BEGIN EXECUTE 'x'; END; $$ LANGUAGE plpgsql;"))).getFirst();

        Token execute = keyword(body, "EXECUTE");
        assertEquals(1, execute.line());
        assertEquals(46, execute.column());
    }

    @Test
    void fallsBackToABareBody() {
        List<RoutineBodies.Body> bodies = RoutineBodies.find(List.of(unit(
                "BEGIN SELECT 1 AS 'one'; EXECUTE format('DROP TABLE %I', 't'); END;")));

        assertEquals(1, bodies.size());
        assertEquals(RoutineBodies.Kind.UNKNOWN, bodies.getFirst().kind());
        assertEquals(null, bodies.getFirst().name());
        assertEquals(1, bodies.getFirst().tokens().stream()
                .filter(token -> !token.isTrivia() && token.matchesKeyword("EXECUTE"))
                .count());
    }

    @Test
    void skipsASingleQuotedBodyAndADeclarationWithoutABody() {
        assertTrue(RoutineBodies.find(List.of(unit(
                "CREATE FUNCTION f() RETURNS int AS 'SELECT 1' LANGUAGE sql;"))).isEmpty());
        assertTrue(RoutineBodies.find(List.of(unit(
                "CREATE FUNCTION f() RETURNS int LANGUAGE sql RETURN 1;"))).isEmpty());
    }

    @Test
    void doesNotExtractTheDeclarationHeader() {
        RoutineBodies.Body body = RoutineBodies.find(List.of(unit(
                "CREATE FUNCTION execute() RETURNS int AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql;"))).getFirst();

        assertEquals("execute", body.name());
        assertTrue(body.tokens().stream()
                .noneMatch(token -> !token.isTrivia() && token.matchesKeyword("EXECUTE")));
    }

    @Test
    void parsesNamedParametersAndTheirModes() {
        RoutineBodies.Body body = RoutineBodies.find(List.of(unit(
                        "CREATE FUNCTION app.f(i_x integer, OUT o_y text, INOUT io_z bigint,"
                                + " VARIADIC i_values integer[]) RETURNS void AS $$ BEGIN NULL; END; $$ LANGUAGE plpgsql;")))
                .getFirst();

        assertEquals(List.of("i_x", "o_y", "io_z", "i_values"),
                body.parameters().stream().map(RoutineBodies.Parameter::name).toList());
        assertEquals(List.of(RoutineBodies.ParameterMode.IN, RoutineBodies.ParameterMode.OUT,
                        RoutineBodies.ParameterMode.INOUT, RoutineBodies.ParameterMode.VARIADIC),
                body.parameters().stream().map(RoutineBodies.Parameter::mode).toList());
    }

    @Test
    void skipsUnnamedParametersAndMultiWordTypes() {
        RoutineBodies.Body body = RoutineBodies.find(List.of(unit(
                        "CREATE FUNCTION app.f(integer, double precision, character varying, numeric(10, 2),"
                                + " app.custom_type, i_real text) RETURNS void AS $$ BEGIN NULL; END; $$ LANGUAGE plpgsql;")))
                .getFirst();

        assertEquals(List.of("i_real"),
                body.parameters().stream().map(RoutineBodies.Parameter::name).toList());
    }

    @Test
    void skipsIntervalAndNationalCharacterTypes() {
        RoutineBodies.Body body = RoutineBodies.find(List.of(unit(
                        "CREATE FUNCTION app.f(interval hour to minute, national character varying, i_real text)"
                                + " RETURNS void AS $$ BEGIN NULL; END; $$ LANGUAGE plpgsql;")))
                .getFirst();

        assertEquals(List.of("i_real"),
                body.parameters().stream().map(RoutineBodies.Parameter::name).toList());
    }

    @Test
    void foldsUnquotedNamesAndKeepsQuotedNames() {
        RoutineBodies.Body unquoted = RoutineBodies.find(List.of(unit(
                        "CREATE FUNCTION App.Foo(I_X integer) RETURNS void AS $$ BEGIN NULL; END;"
                                + " $$ LANGUAGE plpgsql;")))
                .getFirst();
        assertEquals("app.foo", unquoted.name());
        assertEquals("i_x", unquoted.parameters().getFirst().name());

        RoutineBodies.Body quoted = RoutineBodies.find(List.of(unit(
                        "CREATE FUNCTION \"App\".\"Foo\"(\"I_X\" integer) RETURNS void AS $$ BEGIN NULL; END;"
                                + " $$ LANGUAGE plpgsql;")))
                .getFirst();
        assertEquals("App.Foo", quoted.name());
        assertEquals("I_X", quoted.parameters().getFirst().name());
    }

    @Test
    void capturesTheParameterNameTokenPosition() {
        RoutineBodies.Parameter parameter = RoutineBodies.find(List.of(unit("""
                CREATE FUNCTION app.f(
                    i_x integer
                ) RETURNS void AS $$ BEGIN NULL; END; $$ LANGUAGE plpgsql;
                """))).getFirst().parameters().getFirst();

        assertEquals("i_x", parameter.name());
        assertEquals(2, parameter.token().line());
        assertEquals(5, parameter.token().column());
    }
}
