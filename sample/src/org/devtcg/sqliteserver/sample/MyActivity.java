package org.devtcg.sqliteserver.sample;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import org.devtcg.sqliteserver.SQLiteServerConnection;
import org.devtcg.sqliteserver.SQLiteServerConnectionManager;

public class MyActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        new Thread() {
            public void run() {
                smokeTest();
            }
        }.start();
    }

    private SQLiteServerConnectionManager createConnectionManager() {
        return new SQLiteServerConnectionManager(getApplicationContext());
    }

    private SQLiteServerConnection openService() {
        Intent serviceIntent = new Intent(this, TestService.class);
        System.out.println("Opening Service connection to: " + serviceIntent.getComponent());
        return createConnectionManager().openConnectionToService(serviceIntent);
    }

    private SQLiteServerConnection openContentProvider() {
        String authority = TestContentProvider.AUTHORITY;
        System.out.println("Opening ContentProvider connection to: " + authority);
        return createConnectionManager().openConnectionToContentProvider(authority);
    }

    private void smokeTest() {
        SQLiteServerConnection conn = openService();
        try {
            doSmokeTest(conn);
        } finally {
            conn.close();
        }
    }

    private void doSmokeTest(SQLiteServerConnection conn) {
        System.out.println("Deleting all records...");
        conn.delete("test", null, null);

        System.out.println("Inserting records...");
        ContentValues values = new ContentValues();
        values.put("test1", "foo");
        values.put("test2", "bar");
        conn.insert("test", values);
        values.put("test2", "baz");
        conn.insert("test", values);
        values.put("test1", "bla");
        conn.insert("test", values);

        System.out.println("Querying...");
        Cursor foo = conn.rawQuery("SELECT * FROM test", new String[] {});
        try {
            dumpCursor(foo);
        } finally {
            foo.close();
        }
    }

    private static void dumpCursor(Cursor cursor) {
        int columnCount = cursor.getColumnCount();
        System.out.println("count=" + cursor.getCount() + "; columnCount=" + columnCount);
        int rowNum = 0;
        while (cursor.moveToNext()) {
            System.out.println("Row #" + (++rowNum) + ":");
            for (int i = 0; i < columnCount; i++) {
                System.out.print("\t" + cursor.getColumnName(i) + ": ");
                int type = cursor.getType(i);
                Object value;
                switch (type) {
                    case Cursor.FIELD_TYPE_STRING:
                        value = cursor.getString(i);
                        break;
                    case Cursor.FIELD_TYPE_INTEGER:
                        value = cursor.getInt(i);
                        break;
                    case Cursor.FIELD_TYPE_NULL:
                    default:
                        value = null;
                        break;
                }
                System.out.println(value != null ? value.toString() : "null");
            }
        }
    }

}