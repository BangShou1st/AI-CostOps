package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.ingestion.providers.fixtures.ProviderFixtureFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class XlsxStreamingReaderTest {

    private final XlsxStreamingReader reader = new XlsxStreamingReader();

    @Test
    void sheetOrderDoesNotChangeInspection() throws Exception {
        var first = ProviderFixtureFactory.xlsx(workbook(
                sheet("Model usage detail", row("Date", "Model", "API Key"),
                        row("2026-08-01", "mimo-example", "key-1")),
                sheet("Plugin usage detail", row("Date", "Plugin"))));
        var second = ProviderFixtureFactory.xlsx(workbook(
                sheet("Plugin usage detail", row("Date", "Plugin")),
                sheet("Model usage detail", row("Date", "Model", "API Key"),
                        row("2026-08-01", "mimo-example", "key-1"))));

        assertThat(reader.inspect(stream(first)).headersBySheet())
                .isEqualTo(reader.inspect(stream(second)).headersBySheet());
    }

    @Test
    void blankCellsRetainHeaderAlignment() throws Exception {
        var bytes = ProviderFixtureFactory.xlsx(workbook(
                sheet("Model usage detail", row("Date", "Model", "API Key"),
                        row("2026-08-01", null, "key-1"))));

        var rows = new ArrayList<WorkbookRow>();
        reader.forEachRow(stream(bytes), Set.of("Model usage detail"), rows::add);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).values()).containsEntry("Date", "2026-08-01")
                .containsEntry("Model", null)
                .containsEntry("API Key", "key-1");
    }

    @Test
    void headerOnlySheetReturnsSchemaButEmitsNoDataRows() throws Exception {
        var bytes = ProviderFixtureFactory.xlsx(workbook(
                sheet("Model usage detail", row("Date", "Model", "API Key", "Currency",
                        "Consumed Amount", "Input Hit Amount", "Input Miss Amount", "Output Amount",
                        "Total Tokens", "Input Hit Tokens", "Input Miss Tokens", "Output Tokens",
                        "Total audio duration", "Request Count"))));

        assertThat(reader.inspect(stream(bytes)).headers("Model usage detail"))
                .contains(List.of("Date", "Model", "API Key", "Currency", "Consumed Amount",
                        "Input Hit Amount", "Input Miss Amount", "Output Amount", "Total Tokens",
                        "Input Hit Tokens", "Input Miss Tokens", "Output Tokens",
                        "Total audio duration", "Request Count"));
        var rows = new ArrayList<WorkbookRow>();
        reader.forEachRow(stream(bytes), Set.of("Model usage detail"), rows::add);
        assertThat(rows).isEmpty();
    }

    @Test
    void completelyBlankSheetAppearsWithEmptyHeaders() throws Exception {
        var bytes = ProviderFixtureFactory.xlsx(workbook(
                sheet("Model usage detail", row("Date", "Model")),
                sheet("Plugin usage detail")));

        var schema = reader.inspect(stream(bytes));

        assertThat(schema.headers("Plugin usage detail")).contains(List.of());
    }

    @Test
    void chineseHeadersSurviveExactly() throws Exception {
        var bytes = ProviderFixtureFactory.xlsx(workbook(
                sheet("账单汇总", row("时间范围", "用户ID", "组织ID", "客户主体",
                        "充值账户消耗（元）", "赠送账户消耗（元）"),
                        row("2026-08-01 00:00:00 - 2026-08-31 23:59:59", "user-1", "org-1",
                                "某客户", "12.34", "0.00"))));

        var schema = reader.inspect(stream(bytes));

        assertThat(schema.headers("账单汇总"))
                .contains(List.of("时间范围", "用户ID", "组织ID", "客户主体",
                        "充值账户消耗（元）", "赠送账户消耗（元）"));
    }

    @Test
    void tenThousandRowWorkbookStreamsRowByRow() throws Exception {
        var rows = new ArrayList<List<String>>();
        rows.add(row("Date", "Model", "Total Tokens"));
        for (int i = 0; i < 10_000; i++) {
            rows.add(row("2026-08-01", "model-" + i, String.valueOf(i)));
        }
        var bytes = ProviderFixtureFactory.xlsx(workbook(sheet("Model usage detail", rows.toArray(List[]::new))));

        var seen = new ArrayList<WorkbookRow>();
        reader.forEachRow(stream(bytes), Set.of("Model usage detail"), seen::add);

        assertThat(seen).hasSize(10_000);
        assertThat(seen.get(0).rowNumber()).isEqualTo(2);
        assertThat(seen.get(seen.size() - 1).rowNumber()).isEqualTo(10_001);
        assertThat(seen.get(9999).values()).containsEntry("Total Tokens", "9999");
    }

    @Test
    void rowNumbersFollowSpreadsheetPositions() throws Exception {
        var bytes = ProviderFixtureFactory.xlsx(workbook(
                sheet("Model usage detail", row("Date", "Model"),
                        row(null, null),
                        row("2026-08-01", "mimo-example"))));

        var seen = new ArrayList<WorkbookRow>();
        reader.forEachRow(stream(bytes), Set.of("Model usage detail"), seen::add);

        // Header is the first non-empty row (row 1); the blank row is skipped; the
        // data row keeps its real spreadsheet row number (row 3).
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).rowNumber()).isEqualTo(3);
    }

    @Test
    void nonTargetSheetsAreNotEmitted() throws Exception {
        var bytes = ProviderFixtureFactory.xlsx(workbook(
                sheet("Model usage detail", row("Date", "Model"), row("2026-08-01", "m")),
                sheet("Plugin usage detail", row("Date", "Plugin"), row("2026-08-01", "p"))));

        var seen = new ArrayList<WorkbookRow>();
        reader.forEachRow(stream(bytes), Set.of("Model usage detail"), seen::add);

        assertThat(seen).extracting(WorkbookRow::sheetName).containsExactly("Model usage detail");
    }

    @Test
    void missingTargetSheetEmitsNothing() throws Exception {
        var bytes = ProviderFixtureFactory.xlsx(workbook(
                sheet("Model usage detail", row("Date", "Model"), row("2026-08-01", "m"))));

        var seen = new ArrayList<WorkbookRow>();
        reader.forEachRow(stream(bytes), Set.of("账单汇总"), seen::add);

        assertThat(seen).isEmpty();
    }

    @Test
    void malformedWorkbookPackagingIsRejected() {
        var garbage = new byte[1024];
        new java.util.Random(7).nextBytes(garbage);

        assertThatThrownBy(() -> reader.inspect(stream(garbage)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void poiDefaultZipBombDefenseRejectsAbnormalCompression() throws Exception {
        // A valid-looking OOXML zip whose sheet XML deflates from ~2 MiB to a few KiB
        // exceeds POI's built-in ZipSecureFile minimum inflate ratio. Group 2 never
        // relaxes those JVM-wide defaults, so this must be rejected, not parsed.
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write("A".repeat(2 * 1_048_576).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> reader.inspect(stream(bytes.toByteArray())))
                .isInstanceOf(Exception.class);
    }

    // ------------------------------------------------------------------
    // fixture construction helpers
    // ------------------------------------------------------------------

    private static Map<String, List<List<String>>> workbook(Object... sheets) {
        var result = new LinkedHashMap<String, List<List<String>>>();
        for (var sheet : sheets) {
            @SuppressWarnings("unchecked")
            var entry = (Map.Entry<String, List<List<String>>>) sheet;
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map.Entry<String, List<List<String>>> sheet(String name, List<String>... rows) {
        return Map.entry(name, List.of(rows));
    }

    @SafeVarargs
    private static List<String> row(String... cells) {
        // Arrays.asList keeps null entries that represent blank spreadsheet cells.
        return java.util.Arrays.asList(cells);
    }

    private static ByteArrayInputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
