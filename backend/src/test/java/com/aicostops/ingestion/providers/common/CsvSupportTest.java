package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvSupportTest {

    @Test
    void readsHeaderAndRowsWithQuotedCommasAndNewlines() throws java.io.IOException {
        var csv = "name,note\n"
                + "\"Alice, A.\",\"line1\nline2\"\n"
                + "Bob,\"plain\"\n";
        var rows = new ArrayList<Map<String, String>>();

        CsvSupport.forEachRecord(stream(csv), (rowNumber, values) -> rows.add(values));

        assertThat(CsvSupport.readHeader(stream(csv))).containsExactly("name", "note");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("name", "Alice, A.")
                .containsEntry("note", "line1\nline2");
        assertThat(rows.get(1)).containsEntry("note", "plain");
    }

    @Test
    void stripsUtf8BomFromFirstHeaderCell() throws java.io.IOException {
        var csv = "\uFEFFuser_id,model\nu-1,gpt\n";

        assertThat(CsvSupport.readHeader(stream(csv))).containsExactly("user_id", "model");
    }

    @Test
    void reorderedColumnsProduceSameHeaderSet() throws java.io.IOException {
        var a = CsvSupport.readHeader(stream("user_id,model,price\nu,1\n"));
        var b = CsvSupport.readHeader(stream("price,model,user_id\n1,m,2\n"));

        assertThat(a).containsExactlyInAnyOrderElementsOf(b);
    }

    @Test
    void emptyBodyEmitsNoRows() throws java.io.IOException {
        var rows = new ArrayList<Map<String, String>>();

        CsvSupport.forEachRecord(stream("user_id,model\n"), (rowNumber, values) -> rows.add(values));

        assertThat(rows).isEmpty();
    }

    @Test
    void duplicateHeaderIsRejectedAfterNormalization() throws java.io.IOException {
        assertThatThrownBy(() -> CsvSupport.readHeader(stream("model, model \n")))
                .isInstanceOf(CsvSupport.DuplicateCsvHeaderException.class);
    }

    @Test
    void unknownColumnsArePreservedInRowMap() throws java.io.IOException {
        var csv = "user_id,model,extra_future_column\nu-1,gpt,kept-value\n";
        var rows = new ArrayList<Map<String, String>>();

        CsvSupport.forEachRecord(stream(csv), (rowNumber, values) -> rows.add(values));

        assertThat(rows.get(0)).containsEntry("extra_future_column", "kept-value");
    }

    @Test
    void rowNumbersAreOneBasedDataRows() throws java.io.IOException {
        var csv = "a,b\n1,2\n3,4\n";
        var numbers = new ArrayList<Integer>();

        CsvSupport.forEachRecord(stream(csv), (rowNumber, values) -> numbers.add(rowNumber));

        assertThat(numbers).containsExactly(1, 2);
    }

    @Test
    void shorterRowsKeepMissingCellsAsNull() throws java.io.IOException {
        var csv = "a,b,c\n1,2\n";
        var rows = new ArrayList<Map<String, String>>();

        CsvSupport.forEachRecord(stream(csv), (rowNumber, values) -> rows.add(values));

        assertThat(rows.get(0)).containsEntry("a", "1").containsEntry("b", "2")
                .containsEntry("c", null);
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
