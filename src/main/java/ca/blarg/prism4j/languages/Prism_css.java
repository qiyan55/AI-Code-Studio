package ca.blarg.prism4j.languages;

import io.noties.prism4j.Prism4j;
import org.jetbrains.annotations.NotNull;

import static io.noties.prism4j.Prism4j.*;
import static java.util.regex.Pattern.*;

public class Prism_css {

	@NotNull
	public static Grammar create(@NotNull Prism4j prism4j) {

		final Token string = token("string", pattern(
			compile("(?:\"(?:\\\\(?:\\r\\n|[\\s\\S])|(?!\")[^\\\\\\r\\n])*\"|'(?:\\\\(?:\\r\\n|[\\s\\S])|(?!')[^\\\\\\r\\n])*')"),
			false,
			true
		));

		final Token important = token("important", pattern(compile("\\B!important\\b"), false, false, "important"));

		final Token comment = token("comment", pattern(compile("\\/\\*[\\s\\S]*?\\*\\/"), false, true));

		return grammar(
			"css",
			comment,
			token("atrule", pattern(
				compile("@[\\w-]+(?:\\s+(?:\"[\\s\\S]*?\"|'[\\s\\S]*?'|[^;{;\\s]+|\\s+(?!\\s))*?(?=\\s*[{;]))?"),
				false,
				false,
				null,
				grammar("inside",
					token("atrule", pattern(compile("^@[\\w-]+"))),
					token("punctuation", pattern(compile("[;()]"))),
					string
				)
			)),
			token("css-rule", pattern(
				compile("[^{}\\s][^{}]*\\{[\\s\\S]*?\\}"),
				false,
				false,
				null,
				grammar("inside",
					token("selector", pattern(compile("[^{]+(?=\\{)"))),
					token("property", pattern(compile("(^|[{;])\\s*[\\w-]+(?=\\s*:)"), true)),
					important,
					string,
					token("function", pattern(compile("[\\w-]+(?=\\()"))),
					token("punctuation", pattern(compile("[{}():;,]"))),
					token("number", pattern(compile("(?:\\b\\d+\\.?\\d*|\\B\\.\\d+)(?:e[+-]?\\d+)?(?:[a-z%]+)?", CASE_INSENSITIVE)))
				)
			)),
			token("property", pattern(compile("[\\w-]+(?=\\s*:)"))),
			important,
			string,
			token("function", pattern(compile("[\\w-]+(?=\\()"))),
			token("number", pattern(compile("(?:\\b\\d+\\.?\\d*|\\B\\.\\d+)(?:e[+-]?\\d+)?(?:[a-z%]+)?", CASE_INSENSITIVE))),
			token("punctuation", pattern(compile("[{}():;,]")))
		);
	}
}
