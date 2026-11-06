package com.sandrox.osservio.store;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sandrox.osservio.core.PersistenceStatus;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static com.sandrox.osservio.store.PersistenceFixture.expect;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class EvidenceSQLiteTest {
    @Test public void boundedTextKeepsEmptyNulUnicodeAndNullDistinct() {
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            database.execSQL("CREATE TABLE values_under_test (value TEXT)");
            String[] values = {"", "\u0000", "public\u0000例😀", null};
            for (String value : values) database.execSQL("INSERT INTO values_under_test(value) VALUES (?)", new Object[] {value});
            try (Cursor row = database.rawQuery("SELECT " + EvidenceSQLite.textColumn("value", 64) + " FROM values_under_test ORDER BY rowid", null)) {
                for (String value : values) {
                    assertTrue(row.moveToNext()); assertEquals(value == null, row.isNull(0));
                    if (value != null) assertEquals(value, EvidenceSQLite.text(row, 0, 64));
                }
                assertFalse(row.moveToNext());
            }
        }
    }
    @Test public void boundedProjectionRejectsInvalidTypesEncodingAndOversize() throws Exception {
        try (SQLiteDatabase database = SQLiteDatabase.create(null)) {
            database.execSQL("CREATE TABLE values_under_test (value)");
            database.execSQL("INSERT INTO values_under_test(value) VALUES (1), (x'00'), (CAST(x'ff' AS TEXT))");
            char[] large = new char[100000]; Arrays.fill(large, 'x');
            database.execSQL("INSERT INTO values_under_test(value) VALUES (?)", new Object[] {new String(large)});
            try (Cursor row = database.rawQuery("SELECT " + EvidenceSQLite.textColumn("value", 32) + " FROM values_under_test", null)) {
                int count = 0;
                while (row.moveToNext()) {
                    assertEquals(Cursor.FIELD_TYPE_BLOB, row.getType(0)); assertTrue(row.getBlob(0).length <= 33);
                    expect(PersistenceStatus.Error.INVALID_RECORD, () -> EvidenceSQLite.text(row, 0, 32)); count++;
                }
                assertEquals(4, count);
            }
        }
    }
}
