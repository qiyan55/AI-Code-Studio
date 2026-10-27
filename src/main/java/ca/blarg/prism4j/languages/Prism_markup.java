package ca.blarg.prism4j.languages;

import io.noties.prism4j.Prism4j;
import org.jetbrains.annotations.NotNull;

import static io.noties.prism4j.Prism4j.*;
import static java.util.regex.Pattern.*;

public class Prism_markup {

	@NotNull
	public static Grammar create(@NotNull Prism4j prism4j) {

		final Token tag = token("tag", pattern(
			compile("(^|[^<])<\\/?[\\w:-]+(?:\\s+(?:\"[\\s\\S]*?\"|'[\\s\\S]*?'|[^>\"'])+?)*\\s*\\/?>"),
			true,
			true,
			null,
			grammar("inside",
				token("tag", pattern(compile("^<\\/?[\\w:-]+|\\/?>$"), false, false, "namespace")),
				token("attr-value", pattern(
					compile("=(?:\"[\\s\\S]*?\"|'[\\s\\S]*?'|[^\\s'\">`]+)"),
					false,
					false,
					null,
					grammar("inside", token("punctuation", pattern(compile("^=|^[\"']|['\"]$"))))
				)),
				token("punctuation", pattern(compile("^\\/?<|\\/?>$"))),
				token("attr-name", pattern(compile("[\\w:-]+")))
			)
		));

		return grammar(
			"markup",
			token("comment", pattern(compile("<!--[\\s\\S]*?-->"), false, true)),
			token("prolog", pattern(compile("<\\?[\\s\\S]*?\\?>"), false, true)),
			token("doctype", pattern(compile("<!DOCTYPE[\\s\\S]*?>", CASE_INSENSITIVE), false, true)),
			token("cdata", pattern(compile("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>"), false, true)),
			tag,
			token("entity", pattern(compile("&#?[\\da-zA-Z]+;"), false, false, "namespace"))
		);
	}
}
