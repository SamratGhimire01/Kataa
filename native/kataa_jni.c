/*
 * Thin JNI driver over SQLite for Kataa (app.kataa.core.db.KataaNative).
 * Text crosses the boundary as UTF-8 byte arrays (not JNI "modified UTF-8"), so emoji and other
 * 4-byte characters round-trip exactly. Errors become exceptions with SQLite's message.
 */
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sqlite3.h"
#include "sqlite-vec.h"

#define JNI_FN(name) Java_app_kataa_core_db_KataaNative_##name
#define EXPORT __attribute__((visibility("default")))

static jclass g_sqlex;

static void throw_sql(JNIEnv *env, sqlite3 *db, int rc) {
    char buf[512];
    const char *msg = db ? sqlite3_errmsg(db) : sqlite3_errstr(rc);
    snprintf(buf, sizeof buf, "SQLite error %d: %s", rc, msg ? msg : "unknown");
    (*env)->ThrowNew(env, g_sqlex, buf);
}

static int vec_auto_init(sqlite3 *db, char **err, const sqlite3_api_routines *api) {
    (void)api;
    return sqlite3_vec_init(db, err, NULL);
}

EXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass c = (*env)->FindClass(env, "app/kataa/core/db/SqliteException");
    if (!c) return JNI_ERR;
    g_sqlex = (*env)->NewGlobalRef(env, c);
    /* Every connection gets vec0 without load_extension (which is compiled out). */
    sqlite3_auto_extension((void (*)(void))vec_auto_init);
    return JNI_VERSION_1_6;
}

EXPORT jlong JNICALL JNI_FN(open)(JNIEnv *env, jclass cls, jbyteArray pathUtf8, jbyteArray key) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, pathUtf8);
    char *path = malloc((size_t)n + 1);
    (*env)->GetByteArrayRegion(env, pathUtf8, 0, n, (jbyte *)path);
    path[n] = 0;
    sqlite3 *db = NULL;
    int rc = sqlite3_open_v2(path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX | SQLITE_OPEN_URI, NULL);
    free(path);
    if (rc != SQLITE_OK) { throw_sql(env, db, rc); sqlite3_close_v2(db); return 0; }
    if (key) {
        jsize kn = (*env)->GetArrayLength(env, key);
        jbyte *k = (*env)->GetByteArrayElements(env, key, NULL);
        rc = sqlite3_key(db, k, kn);
        memset(k, 0, (size_t)kn);
        (*env)->ReleaseByteArrayElements(env, key, k, JNI_ABORT);
        if (rc != SQLITE_OK) { throw_sql(env, db, rc); sqlite3_close_v2(db); return 0; }
    }
    sqlite3_extended_result_codes(db, 1);
    sqlite3_busy_timeout(db, 5000);
    return (jlong)(intptr_t)db;
}

EXPORT void JNICALL JNI_FN(close)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls;
    sqlite3_close_v2((sqlite3 *)(intptr_t)h);
}

EXPORT jlong JNICALL JNI_FN(prepare)(JNIEnv *env, jclass cls, jlong h, jbyteArray sqlUtf8) {
    (void)cls;
    sqlite3 *db = (sqlite3 *)(intptr_t)h;
    jsize n = (*env)->GetArrayLength(env, sqlUtf8);
    jbyte *sql = (*env)->GetByteArrayElements(env, sqlUtf8, NULL);
    sqlite3_stmt *st = NULL;
    int rc = sqlite3_prepare_v3(db, (const char *)sql, n, SQLITE_PREPARE_PERSISTENT, &st, NULL);
    (*env)->ReleaseByteArrayElements(env, sqlUtf8, sql, JNI_ABORT);
    if (rc != SQLITE_OK) { throw_sql(env, db, rc); return 0; }
    return (jlong)(intptr_t)st;
}

EXPORT void JNICALL JNI_FN(finalize)(JNIEnv *env, jclass cls, jlong s) {
    (void)env; (void)cls;
    sqlite3_finalize((sqlite3_stmt *)(intptr_t)s);
}

EXPORT void JNICALL JNI_FN(reset)(JNIEnv *env, jclass cls, jlong s) {
    (void)env; (void)cls;
    sqlite3_stmt *st = (sqlite3_stmt *)(intptr_t)s;
    sqlite3_reset(st);
    sqlite3_clear_bindings(st);
}

static void check_bind(JNIEnv *env, sqlite3_stmt *st, int rc) {
    if (rc != SQLITE_OK) throw_sql(env, sqlite3_db_handle(st), rc);
}

EXPORT void JNICALL JNI_FN(bindNull)(JNIEnv *env, jclass cls, jlong s, jint i) {
    (void)cls; check_bind(env, (sqlite3_stmt *)(intptr_t)s, sqlite3_bind_null((sqlite3_stmt *)(intptr_t)s, i));
}
EXPORT void JNICALL JNI_FN(bindLong)(JNIEnv *env, jclass cls, jlong s, jint i, jlong v) {
    (void)cls; check_bind(env, (sqlite3_stmt *)(intptr_t)s, sqlite3_bind_int64((sqlite3_stmt *)(intptr_t)s, i, v));
}
EXPORT void JNICALL JNI_FN(bindDouble)(JNIEnv *env, jclass cls, jlong s, jint i, jdouble v) {
    (void)cls; check_bind(env, (sqlite3_stmt *)(intptr_t)s, sqlite3_bind_double((sqlite3_stmt *)(intptr_t)s, i, v));
}
EXPORT void JNICALL JNI_FN(bindText)(JNIEnv *env, jclass cls, jlong s, jint i, jbyteArray utf8) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, utf8);
    jbyte *b = (*env)->GetByteArrayElements(env, utf8, NULL);
    int rc = sqlite3_bind_text((sqlite3_stmt *)(intptr_t)s, i, (const char *)b, n, SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, utf8, b, JNI_ABORT);
    check_bind(env, (sqlite3_stmt *)(intptr_t)s, rc);
}
EXPORT void JNICALL JNI_FN(bindBlob)(JNIEnv *env, jclass cls, jlong s, jint i, jbyteArray blob) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, blob);
    jbyte *b = (*env)->GetByteArrayElements(env, blob, NULL);
    int rc = sqlite3_bind_blob((sqlite3_stmt *)(intptr_t)s, i, b, n, SQLITE_TRANSIENT);
    (*env)->ReleaseByteArrayElements(env, blob, b, JNI_ABORT);
    check_bind(env, (sqlite3_stmt *)(intptr_t)s, rc);
}

/** Returns 1 for a row, 0 when done; throws on error. */
EXPORT jint JNICALL JNI_FN(step)(JNIEnv *env, jclass cls, jlong s) {
    (void)cls;
    sqlite3_stmt *st = (sqlite3_stmt *)(intptr_t)s;
    int rc = sqlite3_step(st);
    if (rc == SQLITE_ROW) return 1;
    if (rc == SQLITE_DONE) return 0;
    throw_sql(env, sqlite3_db_handle(st), rc);
    return -1;
}

EXPORT jint JNICALL JNI_FN(columnCount)(JNIEnv *env, jclass cls, jlong s) {
    (void)env; (void)cls; return sqlite3_column_count((sqlite3_stmt *)(intptr_t)s);
}
EXPORT jint JNICALL JNI_FN(columnType)(JNIEnv *env, jclass cls, jlong s, jint i) {
    (void)env; (void)cls; return sqlite3_column_type((sqlite3_stmt *)(intptr_t)s, i);
}
EXPORT jlong JNICALL JNI_FN(columnLong)(JNIEnv *env, jclass cls, jlong s, jint i) {
    (void)env; (void)cls; return sqlite3_column_int64((sqlite3_stmt *)(intptr_t)s, i);
}
EXPORT jdouble JNICALL JNI_FN(columnDouble)(JNIEnv *env, jclass cls, jlong s, jint i) {
    (void)env; (void)cls; return sqlite3_column_double((sqlite3_stmt *)(intptr_t)s, i);
}
/** UTF-8 bytes of a text column, or null for NULL. */
EXPORT jbyteArray JNICALL JNI_FN(columnText)(JNIEnv *env, jclass cls, jlong s, jint i) {
    (void)cls;
    sqlite3_stmt *st = (sqlite3_stmt *)(intptr_t)s;
    if (sqlite3_column_type(st, i) == SQLITE_NULL) return NULL;
    const unsigned char *t = sqlite3_column_text(st, i);
    int n = sqlite3_column_bytes(st, i);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out && n) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)t);
    return out;
}
EXPORT jbyteArray JNICALL JNI_FN(columnBlob)(JNIEnv *env, jclass cls, jlong s, jint i) {
    (void)cls;
    sqlite3_stmt *st = (sqlite3_stmt *)(intptr_t)s;
    if (sqlite3_column_type(st, i) == SQLITE_NULL) return NULL;
    const void *b = sqlite3_column_blob(st, i);
    int n = sqlite3_column_bytes(st, i);
    jbyteArray out = (*env)->NewByteArray(env, n);
    if (out && n) (*env)->SetByteArrayRegion(env, out, 0, n, (const jbyte *)b);
    return out;
}

EXPORT jint JNICALL JNI_FN(changes)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls; return sqlite3_changes((sqlite3 *)(intptr_t)h);
}
EXPORT jlong JNICALL JNI_FN(lastInsertRowId)(JNIEnv *env, jclass cls, jlong h) {
    (void)env; (void)cls; return sqlite3_last_insert_rowid((sqlite3 *)(intptr_t)h);
}

/** Runs one or more statements with no parameters or results (DDL, PRAGMA, BEGIN, ...). */
EXPORT void JNICALL JNI_FN(exec)(JNIEnv *env, jclass cls, jlong h, jbyteArray sqlUtf8) {
    (void)cls;
    sqlite3 *db = (sqlite3 *)(intptr_t)h;
    jsize n = (*env)->GetArrayLength(env, sqlUtf8);
    char *sql = malloc((size_t)n + 1);
    (*env)->GetByteArrayRegion(env, sqlUtf8, 0, n, (jbyte *)sql);
    sql[n] = 0;
    int rc = sqlite3_exec(db, sql, NULL, NULL, NULL);
    free(sql);
    if (rc != SQLITE_OK) throw_sql(env, db, rc);
}
