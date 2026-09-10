package io.github.yylsping.bilipartfix;

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
        String raw = "{\"code\":0,\"data\":{\"list\":[{\"page\":{\"cid\":2147483647}}]}}";
        assertNull(WatchLaterFix.repairResponse(raw));
    }

    @Test public void narrowsRouteRepairToUgcWatchLaterPlaylists() throws Exception {
        assertEquals("bilibili://video/9876543210", repair("{\"aid\":9876543210,\"uri\":\"bilibili://music/playlist/playpage/7?oid=9876543210&page_type=2&foo=bar\"}").getString("uri"));
        String other = "bilibili://music/playlist/playpage/7?page_type=3";
        assertEquals(other, repair("{\"aid\":9876543210,\"uri\":\"" + other + "\"}").getString("uri"));
    }

    @Test public void errorsAndEmptyResponsesArePreserved() throws Exception {
        assertNull(WatchLaterFix.repairResponse("{\"code\":-101,\"message\":\"请登录\"}"));
        assertNull(WatchLaterFix.repairResponse("{\"code\":0,\"data\":{\"list\":[]}}"));
        assertNull(WatchLaterFix.repairResponse("{\"code\":0,\"data\":{\"list\":null}}"));
    }
}
