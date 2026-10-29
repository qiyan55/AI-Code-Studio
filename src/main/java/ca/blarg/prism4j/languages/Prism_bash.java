package ca.blarg.prism4j.languages;

import io.noties.prism4j.Prism4j;
import org.jetbrains.annotations.NotNull;

import static io.noties.prism4j.Prism4j.*;
import static java.util.regex.Pattern.*;

public class Prism_bash {

	@NotNull
	public static Grammar create(@NotNull Prism4j prism4j) {

		final Token comment = token("comment", pattern(compile("(^|[^&])#.*"), true, false, "comment"));

		final Token string = token("string", pattern(
			compile("\"(?:\\\\.|[^\"\\\\\\r\\n])*\"|'(?:\\\\.|[^'\\\\\\r\\n])*'"),
			false,
			true
		));

		final Token variable = token("variable", pattern(
			compile("\\$\\{[\\w#@?*$!-]+(?::[=+-][^}]*)?\\}|\\$[\\w#@?*$!-]+"),
			false,
			false,
			"variable"
		));

		return grammar(
			"bash",
			comment,
			token("shebang", pattern(compile("^#!.*"), false, true, "comment")),
			string,
			token("keyword", pattern(compile(
				"\\b(?:if|then|else|elif|fi|for|while|until|do|done|case|esac|in|function|select|time|coproc)\\b"
			))),
			token("builtin", pattern(compile(
				"\\b(?:alias|bg|bind|break|builtin|caller|cd|command|compgen|complete|compopt|continue|declare|dirs|disown|echo|enable|eval|exec|exit|export|false|fc|fg|getopts|hash|help|history|jobs|kill|let|local|logout|mapfile|popd|printf|pushd|pwd|read|readarray|readonly|return|set|shift|shopt|source|suspend|test|times|trap|true|type|typeset|ulimit|umask|unalias|unset|wait)\\b"
			), false, false, "keyword")),
			variable,
			token("operator", pattern(compile(
				"&&|\\|\\||<<=?|>>=?|\\|&|\\|&>|>[&>]?|<[<>]?|[=!]=|=~|\\+\\+|--|\\*\\*|\\?\\?"
			))),
			token("number", pattern(compile(
				"\\b(?:0[xX][\\da-fA-F]+|0[oO][0-7]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b"
			))),
			token("function", pattern(compile("[a-zA-Z_]\\w*(?=\\s*\\()"))),
			token("punctuation", pattern(compile("[{}()\\[\\];:,.]")))
		);
	}
}
