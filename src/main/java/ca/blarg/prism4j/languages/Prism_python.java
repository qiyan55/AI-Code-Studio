package ca.blarg.prism4j.languages;

import io.noties.prism4j.Prism4j;
import org.jetbrains.annotations.NotNull;

import static io.noties.prism4j.Prism4j.*;
import static java.util.regex.Pattern.*;

public class Prism_python {

	@NotNull
	public static Grammar create(@NotNull Prism4j prism4j) {

		final Token keyword = token("keyword", pattern(compile(
			"\\b(?:and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield)\\b"
		)));

		final Token string = token("string", pattern(
			compile("(?:[rR]|[uU][rR]|[rR][uU])?\"\"\"[\\s\\S]*?\"\"\"|(?:[rR]|[uU][rR]|[rR][uU])?'''[\\s\\S]*?'''|(?:[bB][rR]|[rR][bB]|[uU][rR]|[rR][uU]|[rR]|[bB]|[uU])?\"(?:\\\\.|[^\"\\\\\\r\\n])*\"|(?:[bB][rR]|[rR][bB]|[uU][rR]|[rR][uU]|[rR]|[bB]|[uU])?'(?:\\\\.|[^'\\\\\\r\\n])*'"),
			false,
			true
		));

		final Token comment = token("comment", pattern(compile("(^|[^\\\\])#.*"), true, false, "comment"));

		final Token function = token("function", pattern(
			compile("(?:^|(?<=\\s))def\\s+[a-zA-Z_]\\w*(?=\\s*\\()"),
			false,
			false,
			null,
			grammar("inside",
				token("keyword", pattern(compile("^def"))),
				token("function", pattern(compile("[a-zA-Z_]\\w*")))
			)
		));

		final Token class_name = token("class-name", pattern(
			compile("(?:^|(?<=\\s))class\\s+[a-zA-Z_]\\w*(?=\\s*[:\\(])"),
			false,
			false,
			null,
			grammar("inside",
				token("keyword", pattern(compile("^class"))),
				token("class-name", pattern(compile("[a-zA-Z_]\\w*")))
			)
		));

		return grammar(
			"python",
			comment,
			string,
			token("decorator", pattern(
				compile("@[\\w.]+"),
				false,
				false,
				"annotation",
				grammar("inside", token("punctuation", pattern(compile("@"))))
			)),
			class_name,
			function,
			keyword,
			token("boolean", pattern(compile("\\b(?:True|False|None)\\b"))),
			token("number", pattern(compile(
				"\\b(?:0[xX][\\da-fA-F]+|0[bB][01]+|0[oO][0-7]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?[jJ]?)\\b"
			))),
			token("operator", pattern(compile(
				"\\+\\+?|--?|!=?|~|\\*\\*?|\\/\\/?|%|<<|>>|&|\\|?|\\^|<=?|>=?|==|:=|@"
			))),
			token("punctuation", pattern(compile("[{}\\[\\];(),.:]")))
		);
	}
}
