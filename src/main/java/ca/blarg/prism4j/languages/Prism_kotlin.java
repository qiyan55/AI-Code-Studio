package ca.blarg.prism4j.languages;

import io.noties.prism4j.GrammarUtils;
import io.noties.prism4j.Prism4j;
import io.noties.prism4j.annotations.Aliases;
import io.noties.prism4j.annotations.Extend;
import org.jetbrains.annotations.NotNull;

import static io.noties.prism4j.Prism4j.*;
import static java.util.regex.Pattern.*;

@Aliases("kt")
@Extend("clike")
public class Prism_kotlin {

	@NotNull
	public static Grammar create(@NotNull Prism4j prism4j) {

		final Token keyword = token("keyword", pattern(compile(
			"\\b(?:abstract|annotation|as|break|by|catch|class|companion|const|constructor|continue|crossinline|data|do|dynamic|else|enum|external|final|finally|for|fun|get|if|import|in|infix|init|inline|inner|interface|internal|is|lateinit|noinline|null|object|open|operator|out|override|package|private|protected|public|reified|return|sealed|set|super|suspend|tailrec|this|throw|try|typealias|val|var|vararg|when|where|while)\\b"
		)));

		final Grammar kotlin = GrammarUtils.extend(GrammarUtils.require(prism4j, "clike"), "kotlin",
			keyword,
			token("string", pattern(
				compile("(?:\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\r\\n])*\"|'(?:\\\\.|[^'\\\\\\r\\n])*')"),
				false,
				true
			)),
			token("number", pattern(compile(
				"\\b(?:0[xX][\\da-fA-F]+|0[bB][01]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[fFlL]?)\\b"
			))),
			token("operator", pattern(compile(
				"(^|[^.])(?:\\.\\.|->|=>|\\?\\.|\\?:|\\*\\*|\\+\\+|--|&&|\\|\\||==|!=|<=|>=|\\+=|-=|\\*=|/=|%=)",
				MULTILINE
			), true)),
			token("annotation", pattern(compile("(^|[^.])@[\\w.]+"), true, false, "punctuation"))
		);

		GrammarUtils.insertBeforeToken(kotlin, "keyword",
			token("boolean", pattern(compile("\\b(?:true|false|null)\\b")))
		);

		return kotlin;
	}
}
