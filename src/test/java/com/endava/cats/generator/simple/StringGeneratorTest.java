package com.endava.cats.generator.simple;

import com.endava.cats.util.CatsModelUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

@QuarkusTest
class StringGeneratorTest {

    @Test
    void shouldGenerateLargeString() {
        String actual = StringGenerator.generateLargeString(2);
        String expected = StringGenerator.FUZZ + StringGenerator.FUZZ;

        Assertions.assertThat(actual).isEqualTo(expected);
    }

    @Test
    void shouldGenerateRandomString() {
        String actual = StringGenerator.generateRandomString();
        Assertions.assertThat(actual).startsWith(StringGenerator.FUZZ);
    }

    @Test
    void givenAPatternThatDoesNotHaveLength_whenGeneratingARandomString_thenTheLengthIsProperlyAdded() {
        String actual = StringGenerator.generate("[A-Z]+", 3, 3);
        Assertions.assertThat(actual).hasSize(3);
    }


    @Test
    void givenAPatternThatDoesHaveLength_whenGeneratingARandomString_thenTheLengthIsProperlyAdded() {
        String actual = StringGenerator.generate("[A-Z]{3}", 4, 4);
        Assertions.assertThat(actual).hasSize(3);
    }

    @Test
    void givenASchemaWithMaxLength_whenGeneratingARightBoundaryString_thenTheGeneratedStringHasProperLength() {
        Schema schema = new StringSchema();
        int maxLength = 10;
        schema.setMaxLength(maxLength);

        String actual = StringGenerator.generateRightBoundString(schema);

        Assertions.assertThat(actual.length()).isGreaterThan(maxLength + 10 - 1);
    }

    @Test
    void givenASchemaWithoutMaxLength_whenGeneratingARightBoundaryString_thenTheGeneratedStringHasDefaultLength() {
        Schema schema = new StringSchema();

        String actual = StringGenerator.generateRightBoundString(schema);
        Assertions.assertThat(actual.length()).isGreaterThanOrEqualTo(StringGenerator.DEFAULT_MAX_LENGTH);
    }

    @Test
    void givenASchemaWithMinLength_whenGeneratingALeftBoundaryString_thenTheGeneratedStringHasProperLength() {
        Schema schema = new StringSchema();
        int minLength = 10;
        schema.setMinLength(minLength);

        String actual = StringGenerator.generateLeftBoundString(schema);

        Assertions.assertThat(actual.length()).isLessThan(minLength);
    }

    @Test
    void givenASchemaWithoutMinLength_whenGeneratingALeftBoundaryString_thenTheGeneratedStringHasDefaultLength() {
        Schema schema = new StringSchema();

        String actual = StringGenerator.generateLeftBoundString(schema);
        Assertions.assertThat(actual).isEmpty();
    }

    @Test
    void shouldReturnLongLengthWhenMaxLengthIsIntegerMax() {
        Schema<String> schema = new StringSchema();
        schema.setMaxLength(Integer.MAX_VALUE - 2);
        int maxExpected = Integer.MAX_VALUE / 100;
        long actual = StringGenerator.getRightBoundaryLength(schema);

        Assertions.assertThat(actual).isEqualTo(maxExpected);
    }

    @ParameterizedTest
    @CsvSource({"0,0,10,30", "0,20,10,30"})
    void shouldGenerateValueWhenPatternAndNoMinMax(int min, int max, int left, int right) {
        String generated = StringGenerator.generate("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$", min, max);
        Assertions.assertThat(generated).hasSizeBetween(left, right);
    }


    @ParameterizedTest
    @CsvSource({"[A-Z]+", "^[^\\s]+(\\s+[^\\s]+)*$", "^[\\w\\u00C0-\\u02AF]+(\\s+[\\w\\u00C0-\\u02AF]+)*$"})
    void shouldGenerateStringForSpecificRegexes(String regex) {
        String generated = StringGenerator.generate(regex, 2048, 2048);

        Assertions.assertThat(generated).matches(regex).hasSizeBetween(2048, 2048);
    }

    @ParameterizedTest
    @CsvSource({"te`st,test", "boost,boost"})
    void shouldSanitize(String input, String expected) {
        String sanitized = StringGenerator.sanitize(input);
        Assertions.assertThat(sanitized).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"^\\+?[1-9]\\d{6,15}$;16", "[A-Z]+;20", "[A-Z0-9]{13,18};18", "[0-9]+;10", "^(?=[^\\s])(?=.*[^\\s]$)(?=^(?:(?!<|>|%3e|%3c).)*$).*$;2048", "M|F;1"}, delimiterString = ";")
    void shouldGenerateFixedLength(String pattern, int length) {
        String fixedLengthGenerated = StringGenerator.generateExactLength(new Schema<String>(), pattern, length);

        Assertions.assertThat(fixedLengthGenerated).hasSize(length).matches(pattern);
    }

    @Test
    void shouldGenerateWhenNegativeLength() {
        String generated = StringGenerator.generate("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$", -1, -1);
        Assertions.assertThat(generated).hasSize(17).matches("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$");
    }

    @Test
    void shouldGenerateWhenOrEmpty() {
        String generated = StringGenerator.generate("^$|^(?i)(http:\\/\\/|https:\\/\\/)([a-z0-9./\\-_.~+=:;%&?]+)$", 100, 100);
        Assertions.assertThat(generated).hasSize(100).matches("^(?i)(http://|https://)([a-z0-9./\\-_.~+=:;%&?]+)");
    }

    @Test
    void shouldRemoveCaseInsensitive() {
        String generated = StringGenerator.cleanPattern("^(?i)(http:\\/\\/|https:\\/\\/)([a-z0-9./\\-_.~+=:;%&?]+)$");
        Assertions.assertThat(generated).isEqualTo("^(http:\\/\\/|https:\\/\\/)([a-z0-9./\\-_.~+=:;%&?]+)");
        Assertions.assertThat(StringGenerator.generate(generated, 100, 100)).hasSize(100);
    }

    @Test
    void shouldGenerateLeftBoundaryForEnum() {
        Schema<String> schema = new StringSchema();
        schema.setEnum(List.of("test", "b", "c"));
        String generated = StringGenerator.generateLeftBoundString(schema);
        Assertions.assertThat(generated).hasSize(4);
    }

    @Test
    void shouldGenerateEmptyWhenLengthZero() {
        String generated = StringGenerator.generateExactLength(new Schema<String>(), "^[A-Z]{3}$", 0);
        Assertions.assertThat(generated).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"(^$)|^(((\\+|00)(9[976]\\d|8[987530]\\d|6[987]\\d|5[90]\\d|42\\d|3[875]\\d|2[98654321]\\d|9[8543210]|8[6421]|6[6543210]|5[87654321]|4[987654310]|3[9643210]|2[70]|7|1)([[:space:]]?))?([\\d]{4}\\d{1,9})$);^(((\\+|00)(9[976]\\d|8[987530]\\d|6[987]\\d|5[90]\\d|42\\d|3[875]\\d|2[98654321]\\d|9[8543210]|8[6421]|6[6543210]|5[87654321]|4[987654310]|3[9643210]|2[70]|7|1)([[:space:]]?))?([\\d]{4}\\d{1,9})$)",
            "/^[^<>]*$/;^[^<>]*", "^[A-Z-a-z0-9]{4}[A-Z-a-z]{2}[A-Z-a-z0-9]{2}([A-Z-a-z0-9]{3})?$;^[A-Z-a-z0-9]{4}[A-Z-a-z]{2}[A-Z-a-z0-9]{2}([A-Z-a-z0-9]{3})?", "/^.*[a-z]+.*$/i;^.*[a-z]+.*", ";[a-zA-Z0-9]+", "/^[a-z];^[a-z]"}, delimiter = ';')
    void shouldCleanPattern(String pattern, String expected) {
        String cleaned = StringGenerator.cleanPattern(pattern);
        Assertions.assertThat(cleaned).isEqualTo(expected);
    }

    @Test
    void shouldGenerateMinLengthWhenMaxLengthHigherThanMinLength() {
        Schema<String> schema = new StringSchema();
        schema.setMinLength(10);
        schema.setMaxLength(20);
        String generated = StringGenerator.generateLeftBoundString(schema);
        Assertions.assertThat(generated).hasSize(9);
    }

    @Test
    void shouldGenerateWhenStartEndSlash() {
        String generated = StringGenerator.generate("/^.*[a-z]+.*$/i", -1, -1);
        Assertions.assertThat(generated).isNotBlank();
    }

    @Test
    void shouldGenerateWhenOrAndFixLength() {
        String generated = StringGenerator.generate("(^[A-Z][0-9]+$)|(^[ABCDEFGHJKLMNPRSTVWX1-9][5CMBL][ED][0-5][0-9][0-7][0-9][0-9][0-9]$)", -1, -1);
        Assertions.assertThat(generated).hasSizeGreaterThan(5);
    }

    @ParameterizedTest
    @CsvSource(value = {"[A-Z0-9];false", "[A-Z]+;true", "[A-Z]{3};true", "[A-Z]{3,};true", "[A-Z]{,3};false"}, delimiter = ';')
    void shouldTestInlineLength(String pattern, boolean expected) {
        boolean actual = StringGenerator.hasLength(pattern);
        Assertions.assertThat(actual).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(value = {"[A-Z];4;4", "[A-Z][A-Z];2;2", "[A-Z];1;1", "[A-Z][0-9];2;3"}, delimiter = ';')
    void shouldGenerateWhenPatternDoesNotHaveLengthButHasMinOrMax(String regex, int min, int max) {
        String generated = StringGenerator.generate(regex, min, max);
        Assertions.assertThat(generated).hasSize(min);
    }

    @Test
    void shouldGenerateComplexEmailRegex() {
        String regex = "^((([a-z]|\\d|[!#\\$%&'\\\\+\\-\\/=\\?\\^_`{\\|}~]|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])+(\\.([a-z]|\\d|[!#\\$%&'\\\\+\\-\\/=\\?\\^`{\\|}~]|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])+))|((\\x22)((((\\x20|\\x09)(\\x0d\\x0a))?(\\x20|\\x09)+)?(([\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]|\\x21|[\\x23-\\x5b]|[\\x5d-\\x7e]|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])|(\\\\([\\x01-\\x09\\x0b\\x0c\\x0d-\\x7f]|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF]))))(((\\x20|\\x09)(\\x0d\\x0a))?(\\x20|\\x09)+)?(\\x22)))@((([a-z]|\\d|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])|(([a-z]|\\d|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])([a-z]|\\d|-|||~|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])*([a-z]|\\d|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])))\\.)+(([a-z]|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])+|(([a-z]|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])+([a-z]+|\\d|-|\\.{0,1}|_|~|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])?([a-z]|[\\u00A0-\\uD7FF\\uF900-\\uFDCF\\uFDF0-\\uFFEF])))$";
        String generated = StringGenerator.generate(regex, 1, 6000);
        Assertions.assertThat(generated).hasSizeBetween(1, 6000);
    }

    @ParameterizedTest
    @CsvSource(value = {
            "[a-zA-Z0-9_]+@[a-zA-Z0-9_]+\\.[a-zA-Z]{2,4};\\w+@\\w+\\.[a-zA-Z]{2,4}",
            "ab{1,}c{1}d{0,1};ab+c{1}d?",
            "^(https?://)?([a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+)(/[a-zA-Z0-9_\\.~\\(\\)\\-]+)*/?$;^(https?://)?(\\w+(\\.\\w+)+)(/[a-zA-Z0-9_\\.~\\(\\)\\-]+)*/?$",
            "^[a-zA-Z0-9_]+([\\.-]?[a-zA-Z0-9_]+)*@[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)*\\.[a-zA-Z]{2,}$;^\\w+([\\.-]?\\w+)*@\\w+(\\.\\w+)*\\.[a-zA-Z]{2,}$",
            "(19|20)[0-9]{2}[\\s\\t\\r\\n\\f]{0,1}(0[1-9]|1[012])[\\s\\t\\r\\n\\f]{0,1}(0[1-9]|[12][0-9]|3[01]);(19|20)\\d{2}\\s?(0[1-9]|1[012])\\s?(0[1-9]|[12]\\d|3[01])",
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*[0-9])(?=.*[^\\da-zA-Z])(?=.*[^\\w]).{8,}$;^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^\\da-zA-Z])(?=.*W).{8,}$",
            "^([0-9]{1,3}\\.){3}[0-9]{1,3}(:[0-9]{1,5})?$;^(\\d{1,3}\\.){3}\\d{1,3}(:\\d{1,5})?$",
            "^([0-9]{4}-[0-9]{2}-[0-9]{2}[\\s\\t\\r\\n\\f]{0,1}[0-9]{2}:[0-9]{2}:[0-9]{2})\\s\\[([^\\]]+)\\]\\s(.*)$;^(\\d{4}-\\d{2}-\\d{2}\\s?\\d{2}:\\d{2}:\\d{2})\\s\\[([^\\]]+)\\]\\s(.*)$",
            "^(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|6(?:011|5[0-9]{2})[0-9]{12})$;^(?:4\\d{12}(?:\\d{3})?|5[1-5]\\d{14}|3[47]\\d{13}|3(?:0[0-5]|[68]\\d)\\d{11}|6(?:011|5\\d{2})\\d{12})$",},
            delimiter = ';')
    void shouldFlatten(String regex, String expected) {
        String flattened = RegexFlattener.flattenRegex(regex);
        Assertions.assertThat(flattened).isEqualTo(expected);

        String generated = StringGenerator.generate(flattened, 1, 6000);
        Assertions.assertThat(generated).hasSizeBetween(1, 6000).matches(regex);
    }

    @Test
    void shouldGenerateEmail() {
        Schema<String> schema = new Schema<>();
        schema.addExtension(CatsModelUtils.X_CATS_FIELD_NAME, "email");
        schema.setPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
        String generated = StringGenerator.generateExactLength(schema, "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", 200);

        Assertions.assertThat(generated).hasSize(200);
    }

    @Test
    void shouldGenerateEmailBasedOnMixMax() {
        Schema<String> schema = new Schema<>();
        schema.setPattern("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
        schema.setMaxLength(100);
        schema.addExtension(CatsModelUtils.X_CATS_FIELD_NAME, "email");
        String generated = StringGenerator.generateValueBasedOnMinMax(schema);

        Assertions.assertThat(generated).hasSize(100);
    }

}
