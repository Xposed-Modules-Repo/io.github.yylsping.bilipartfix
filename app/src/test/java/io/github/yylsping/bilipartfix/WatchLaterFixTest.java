package io.github.yylsping.bilipartfix;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class WatchLaterFixTest {
    private static JSONObject repair(String item) throws Exception {
        String raw = "{\"code\":0,\"data\":{\"count\":1,\"list\":[" + item + "]}}";
        String result = WatchLaterFix.repairResponse(raw);
        return new JSONObject(result == null ? raw : result).getJSONObject("data")
                .getJSONArray("list").getJSONObject(0);
    }

    private static void assertUntouched(String raw) throws Exception {
        assertNull(WatchLaterFix.repairResponse(raw));
    }

    // ---- CID / schema ----

    @Test public void removesOnlyUnusedOverflowingCid() throws Exception {
        JSONObject item = repair("{\"cid\":4294967311,\"page\":{\"cid\":4294967311,\"page\":2},\"title\":\"例子\"}");
        assertEquals(4294967311L, item.getLong("cid"));
        assertFalse(item.getJSONObject("page").has("cid"));
        assertEquals(2, item.getJSONObject("page").getInt("page"));
        assertEquals("例子", item.getString("title"));
    }

    @Test public void primaryCidWinsWhenPageDiffers() throws Exception {
        JSONObject item = repair("{\"cid\":4294967311,\"page\":{\"cid\":4294967322}}");
        assertEquals(4294967311L, item.getLong("cid"));
        assertFalse(item.getJSONObject("page").has("cid"));
    }

    @Test public void missingPrimaryCidIsRecoveredWithoutTruncation() throws Exception {
        assertEquals(4294967311L, repair("{\"page\":{\"cid\":4294967311}}").getLong("cid"));
    }

    @Test public void signedIntBoundaryRemainsUntouched() throws Exception {
        assertUntouched("{\"code\":0,\"data\":{\"list\":[{\"page\":{\"cid\":2147483647}}]}}");
    }

    @Test public void smallPageCidRemainsUntouched() throws Exception {
        assertUntouched("{\"code\":0,\"data\":{\"list\":[{\"cid\":12345,\"page\":{\"cid\":12345}}]}}");
    }

    @Test public void stringFormCidIsHandledCompatibly() throws Exception {
        JSONObject item = repair("{\"page\":{\"cid\":\"4294967311\",\"page\":1}}");
        assertEquals(4294967311L, item.getLong("cid"));
        assertFalse(item.getJSONObject("page").has("cid"));
        assertEquals(1, item.getJSONObject("page").getInt("page"));
    }

    @Test public void missingOrNullPageIsTolerated() throws Exception {
        assertUntouched("{\"code\":0,\"data\":{\"list\":[{\"cid\":4294967311}]}}");
        assertUntouched("{\"code\":0,\"data\":{\"list\":[{\"cid\":4294967311,\"page\":null}]}}");
    }

    @Test public void unrelatedLargeIntegersArePreserved() throws Exception {
        JSONObject item = repair("{\"aid\":9876543210,\"tid\":4294967333,"
                + "\"stat\":{\"view\":9876543211},\"page\":{\"cid\":4294967311}}");
        assertEquals(9876543210L, item.getLong("aid"));
        assertEquals(4294967333L, item.getLong("tid"));
        assertEquals(9876543211L, item.getJSONObject("stat").getLong("view"));
    }

    // ---- URI / route ----

    @Test public void narrowsRouteRepairToUgcWatchLaterPlaylists() throws Exception {
        assertEquals("bilibili://video/9876543210", repair("{\"aid\":9876543210,\"uri\":\"bilibili://music/playlist/playpage/7?oid=9876543210&page_type=2&foo=bar\"}").getString("uri"));
        String other = "bilibili://music/playlist/playpage/7?page_type=3";
        assertEquals(other, repair("{\"aid\":9876543210,\"uri\":\"" + other + "\"}").getString("uri"));
    }

    @Test public void pageTypePositionAndOrderDoNotMatter() throws Exception {
        String[] uris = {
                "bilibili://music/playlist/playpage/7?page_type=2",
                "bilibili://music/playlist/playpage/7?page_type=2&oid=1",
                "bilibili://music/playlist/playpage/7?oid=1&page_type=2",
                "bilibili://music/playlist/playpage/7?oid=1&page_type=2&foo=a%20b",
                "bilibili://music/playlist/playpage/7?foo=bar&page_type=2#frag"};
        for (String uri : uris) {
            assertTrue("should match: " + uri, WatchLaterFix.isUgcPlaylistRoute(uri));
        }
    }

    @Test public void encodedPageTypeIsStillRecognized() throws Exception {
        assertTrue(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7?page_type=%32"));
        assertTrue(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7?%70age_type=2"));
    }

    @Test public void lookalikeParametersDoNotMatch() throws Exception {
        assertFalse(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7?other_page_type=2"));
        assertFalse(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7?page_type=22"));
        assertFalse(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7?page_type="));
        assertFalse(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7?page_type"));
        assertFalse(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7"));
        assertFalse(WatchLaterFix.isUgcPlaylistRoute(
                "bilibili://music/playlist/playpage/7?foo=page_type%3D2"));
        assertFalse(WatchLaterFix.isUgcPlaylistRoute(null));
    }

    @Test public void cardTypeNeverGatesTheRouteRepair() throws Exception {
        // Neither legacy click handler nor adapter reads card_type; the 7040300
        // model has no such field at all. It must not block the rewrite.
        assertEquals("bilibili://video/42", repair("{\"aid\":42,\"card_type\":5,"
                + "\"uri\":\"bilibili://music/playlist/playpage/7?page_type=2\"}").getString("uri"));
        assertEquals("bilibili://video/42", repair("{\"aid\":42,"
                + "\"uri\":\"bilibili://music/playlist/playpage/7?page_type=2\"}").getString("uri"));
    }

    @Test public void videoUrisAreNeverRewritten() throws Exception {
        String uri = "bilibili://video/9876543210?cid=1&page=2";
        assertEquals(uri, repair("{\"aid\":9876543210,\"uri\":\"" + uri + "\"}").getString("uri"));
    }

    @Test public void missingOrZeroAidNeverProducesBadUri() throws Exception {
        String uri = "bilibili://music/playlist/playpage/7?page_type=2";
        assertEquals(uri, repair("{\"uri\":\"" + uri + "\"}").getString("uri"));
        assertEquals(uri, repair("{\"aid\":0,\"uri\":\"" + uri + "\"}").getString("uri"));
    }

    // ---- response / parser ----

    @Test public void errorsAndEmptyResponsesArePreserved() throws Exception {
        assertUntouched("{\"code\":-101,\"message\":\"请登录\"}");
        assertUntouched("{\"code\":0,\"data\":{\"list\":[]}}");
        assertUntouched("{\"code\":0,\"data\":{\"list\":null}}");
        assertUntouched("{\"code\":0,\"data\":null}");
        assertUntouched("{\"code\":0}");
    }

    @Test public void malformedJsonFailsOpen() {
        assertThrows(JSONException.class,
                () -> WatchLaterFix.repairResponse("{not json"));
    }

    @Test public void nonObjectListEntriesAreSkipped() throws Exception {
        // Non-object entries must survive untouched without breaking the repair.
        assertUntouched("{\"code\":0,\"data\":{\"list\":[null,\"x\",1]}}");
        String raw = "{\"code\":0,\"data\":{\"list\":[null,"
                + "{\"page\":{\"cid\":4294967311}}]}}";
        JSONArray list = new JSONObject(WatchLaterFix.repairResponse(raw))
                .getJSONObject("data").getJSONArray("list");
        assertEquals(JSONObject.NULL, list.get(0));
        assertEquals(4294967311L, list.getJSONObject(1).getLong("cid"));
    }

    @Test public void unrelatedFieldsSurviveRepair() throws Exception {
        JSONObject item = repair("{\"aid\":7,\"cid\":4294967311,\"title\":\"t\","
                + "\"owner\":{\"mid\":99},\"pages\":[{\"cid\":5}],"
                + "\"page\":{\"cid\":4294967311,\"duration\":60}}");
        assertEquals(7, item.getLong("aid"));
        assertEquals("t", item.getString("title"));
        assertEquals(99, item.getJSONObject("owner").getLong("mid"));
        assertEquals(5, item.getJSONArray("pages").getJSONObject(0).getLong("cid"));
        assertEquals(60, item.getJSONObject("page").getInt("duration"));
    }
}
