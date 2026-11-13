package fr.cobblepulse.storage;

import com.google.gson.reflect.TypeToken;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.cobblepulse.config.TomlFile;
import fr.cobblepulse.core.Json;
import fr.cobblepulse.core.Loc;
import fr.cobblepulse.data.ClanData;
import fr.cobblepulse.data.LeagueData;
import fr.cobblepulse.data.PlayerData;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mode 2 : MySQL via un pool HikariCP.
 * Tables : cp_clans, cp_clan_members, cp_league_holders, cp_player_badges (+ cp_players, cp_kv).
 */
public final class MySqlStorage implements Storage {
    private static final Type STRING_LIST = new TypeToken<List<String>>() {}.getType();
    private static final Type MON_LIST = new TypeToken<List<PlayerData.MonEntry>>() {}.getType();
    private static final Type LONG_MAP = new TypeToken<Map<String, Long>>() {}.getType();
    private static final Type REQ_MAP = new TypeToken<LinkedHashMap<String, ClanData.JoinRequest>>() {}.getType();

    private final TomlFile cfg;
    private final String p;
    private HikariDataSource ds;

    public MySqlStorage(TomlFile cfg) {
        this.cfg = cfg;
        this.p = cfg.str("mysql.table_prefix", "cp_");
    }

    @Override
    public String name() {
        return "MySQL (" + cfg.str("mysql.host", "localhost") + "/" + cfg.str("mysql.database", "cobblepulse") + ")";
    }

    @Override
    public void init() throws Exception {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ignored) {
        }
        HikariConfig hc = new HikariConfig();
        String url = "jdbc:mysql://" + cfg.str("mysql.host", "localhost") + ":" + cfg.i("mysql.port", 3306) + "/"
                + cfg.str("mysql.database", "cobblepulse") + "?useSSL=" + cfg.b("mysql.use_ssl", false)
                + "&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC";
        hc.setJdbcUrl(url);
        hc.setUsername(cfg.str("mysql.username", "root"));
        hc.setPassword(cfg.str("mysql.password", ""));
        hc.setMaximumPoolSize(Math.max(2, cfg.i("mysql.pool_size", 6)));
        hc.setPoolName("CobblePulse-MySQL");
        hc.setConnectionTimeout(10_000);
        hc.addDataSourceProperty("cachePrepStmts", "true");
        hc.addDataSourceProperty("prepStmtCacheSize", "250");
        hc.addDataSourceProperty("useServerPrepStmts", "true");
        ds = new HikariDataSource(hc);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clans (id VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, tag VARCHAR(16) NOT NULL,"
                    + " tag_color VARCHAR(16), leader VARCHAR(36), mode VARCHAR(16), description TEXT, influence BIGINT DEFAULT 0,"
                    + " war_wins INT DEFAULT 0, war_losses INT DEFAULT 0, war_draws INT DEFAULT 0, pvp_wins INT DEFAULT 0, pvp_losses INT DEFAULT 0,"
                    + " created_at BIGINT, invites TEXT, requests TEXT, inventory MEDIUMTEXT) CHARACTER SET utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "clan_members (clan_id VARCHAR(36) NOT NULL, player_uuid VARCHAR(36) NOT NULL,"
                    + " player_name VARCHAR(32), role VARCHAR(16), joined_at BIGINT, PRIMARY KEY (clan_id, player_uuid), INDEX(player_uuid)) CHARACTER SET utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "league_holders (post_id VARCHAR(32) PRIMARY KEY, kind VARCHAR(16), holder_uuid VARCHAR(36),"
                    + " holder_name VARCHAR(32), types VARCHAR(64), is_open TINYINT(1) DEFAULT 0, tp_holder TEXT, tp_challenger TEXT,"
                    + " assigned_at BIGINT, last_holder_change BIGINT, consecutive_refusals INT DEFAULT 0, holder_last_seen BIGINT,"
                    + " applied_prefix VARCHAR(128)) CHARACTER SET utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "player_badges (player_uuid VARCHAR(36) NOT NULL, badge_id VARCHAR(32) NOT NULL,"
                    + " item_id VARCHAR(128), obtained_at BIGINT, holder_uuid VARCHAR(36), holder_name VARCHAR(32), holder_types VARCHAR(64),"
                    + " duration_seconds BIGINT, turns INT, challenger_team TEXT, holder_team TEXT, battle_id VARCHAR(36), note TEXT,"
                    + " PRIMARY KEY (player_uuid, badge_id)) CHARACTER SET utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32), data MEDIUMTEXT) CHARACTER SET utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "kv (k VARCHAR(128) PRIMARY KEY, v MEDIUMTEXT) CHARACTER SET utf8mb4");
        }
    }

    @Override
    public void close() {
        if (ds != null) ds.close();
    }

    @Override
    public List<ClanData> loadClans() throws SQLException {
        Map<String, ClanData> map = new LinkedHashMap<>();
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + p + "clans"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ClanData d = new ClanData();
                    d.id = rs.getString("id");
                    d.name = rs.getString("name");
                    d.tag = rs.getString("tag");
                    d.tagColor = rs.getString("tag_color");
                    d.leader = rs.getString("leader");
                    d.mode = rs.getString("mode");
                    d.description = rs.getString("description");
                    d.influence = rs.getLong("influence");
                    d.warWins = rs.getInt("war_wins");
                    d.warLosses = rs.getInt("war_losses");
                    d.warDraws = rs.getInt("war_draws");
                    d.pvpWins = rs.getInt("pvp_wins");
                    d.pvpLosses = rs.getInt("pvp_losses");
                    d.createdAt = rs.getLong("created_at");
                    String inv = rs.getString("invites");
                    if (inv != null) d.invites = Json.COMPACT.fromJson(inv, LONG_MAP);
                    String req = rs.getString("requests");
                    if (req != null) d.requests = Json.COMPACT.fromJson(req, REQ_MAP);
                    if (d.invites == null) d.invites = new HashMap<>();
                    if (d.requests == null) d.requests = new LinkedHashMap<>();
                    d.inventory = rs.getString("inventory");
                    map.put(d.id, d);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + p + "clan_members"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ClanData d = map.get(rs.getString("clan_id"));
                    if (d == null) continue;
                    d.members.put(rs.getString("player_uuid"),
                            new ClanData.ClanMember(rs.getString("player_name"), rs.getString("role"), rs.getLong("joined_at")));
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    @Override
    public void saveClan(ClanData d) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("REPLACE INTO " + p + "clans (id,name,tag,tag_color,leader,mode,description,influence,"
                        + "war_wins,war_losses,war_draws,pvp_wins,pvp_losses,created_at,invites,requests,inventory) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, d.id);
                    ps.setString(2, d.name);
                    ps.setString(3, d.tag);
                    ps.setString(4, d.tagColor);
                    ps.setString(5, d.leader);
                    ps.setString(6, d.mode);
                    ps.setString(7, d.description);
                    ps.setLong(8, d.influence);
                    ps.setInt(9, d.warWins);
                    ps.setInt(10, d.warLosses);
                    ps.setInt(11, d.warDraws);
                    ps.setInt(12, d.pvpWins);
                    ps.setInt(13, d.pvpLosses);
                    ps.setLong(14, d.createdAt);
                    ps.setString(15, Json.COMPACT.toJson(d.invites));
                    ps.setString(16, Json.COMPACT.toJson(d.requests));
                    ps.setString(17, d.inventory);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + p + "clan_members WHERE clan_id=?")) {
                    ps.setString(1, d.id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + p + "clan_members (clan_id,player_uuid,player_name,role,joined_at) VALUES (?,?,?,?,?)")) {
                    for (Map.Entry<String, ClanData.ClanMember> e : d.members.entrySet()) {
                        ps.setString(1, d.id);
                        ps.setString(2, e.getKey());
                        ps.setString(3, e.getValue().name);
                        ps.setString(4, e.getValue().role);
                        ps.setLong(5, e.getValue().joinedAt);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    @Override
    public void deleteClan(String clanId) throws SQLException {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + p + "clan_members WHERE clan_id=?")) {
                ps.setString(1, clanId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + p + "clans WHERE id=?")) {
                ps.setString(1, clanId);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public LeagueData loadLeague() throws SQLException {
        LeagueData data = new LeagueData();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM " + p + "league_holders");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                LeagueData.PostData d = new LeagueData.PostData();
                d.id = rs.getString("post_id");
                d.kind = rs.getString("kind");
                d.holderUuid = rs.getString("holder_uuid");
                d.holderName = rs.getString("holder_name");
                String types = rs.getString("types");
                d.types = new ArrayList<>();
                if (types != null && !types.isBlank()) d.types.addAll(List.of(types.split(",")));
                d.open = rs.getBoolean("is_open");
                String th = rs.getString("tp_holder");
                if (th != null) d.tpHolder = Json.COMPACT.fromJson(th, Loc.class);
                String tc = rs.getString("tp_challenger");
                if (tc != null) d.tpChallenger = Json.COMPACT.fromJson(tc, Loc.class);
                d.assignedAt = rs.getLong("assigned_at");
                d.lastHolderChange = rs.getLong("last_holder_change");
                d.consecutiveRefusals = rs.getInt("consecutive_refusals");
                d.holderLastSeen = rs.getLong("holder_last_seen");
                d.appliedPrefix = rs.getString("applied_prefix");
                data.posts.add(d);
            }
        }
        return data.posts.isEmpty() ? null : data;
    }

    @Override
    public void saveLeague(LeagueData data) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("REPLACE INTO " + p + "league_holders (post_id,kind,holder_uuid,holder_name,types,is_open,"
                     + "tp_holder,tp_challenger,assigned_at,last_holder_change,consecutive_refusals,holder_last_seen,applied_prefix) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (LeagueData.PostData d : data.posts) {
                ps.setString(1, d.id);
                ps.setString(2, d.kind);
                ps.setString(3, d.holderUuid);
                ps.setString(4, d.holderName);
                ps.setString(5, String.join(",", d.types));
                ps.setBoolean(6, d.open);
                ps.setString(7, d.tpHolder == null ? null : Json.COMPACT.toJson(d.tpHolder));
                ps.setString(8, d.tpChallenger == null ? null : Json.COMPACT.toJson(d.tpChallenger));
                ps.setLong(9, d.assignedAt);
                ps.setLong(10, d.lastHolderChange);
                ps.setInt(11, d.consecutiveRefusals);
                ps.setLong(12, d.holderLastSeen);
                ps.setString(13, d.appliedPrefix);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public PlayerData loadPlayer(UUID uuid) throws SQLException {
        PlayerData d = null;
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT data FROM " + p + "players WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) d = Json.COMPACT.fromJson(rs.getString("data"), PlayerData.class);
                }
            }
            if (d == null) return null;
            d.badges = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + p + "player_badges WHERE player_uuid=? ORDER BY obtained_at")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        PlayerData.BadgeRecord b = new PlayerData.BadgeRecord();
                        b.postId = rs.getString("badge_id");
                        b.itemId = rs.getString("item_id");
                        b.obtainedAt = rs.getLong("obtained_at");
                        b.holderUuid = rs.getString("holder_uuid");
                        b.holderName = rs.getString("holder_name");
                        String ht = rs.getString("holder_types");
                        b.holderTypes = new ArrayList<>();
                        if (ht != null && !ht.isBlank()) b.holderTypes.addAll(List.of(ht.split(",")));
                        b.durationSeconds = rs.getLong("duration_seconds");
                        b.turns = rs.getInt("turns");
                        String ct = rs.getString("challenger_team");
                        if (ct != null) b.challengerTeam = Json.COMPACT.fromJson(ct, MON_LIST);
                        String hteam = rs.getString("holder_team");
                        if (hteam != null) b.holderTeam = Json.COMPACT.fromJson(hteam, MON_LIST);
                        b.battleId = rs.getString("battle_id");
                        b.note = rs.getString("note");
                        d.badges.put(b.postId, b);
                    }
                }
            }
        }
        return d;
    }

    @Override
    public void savePlayer(PlayerData d) throws SQLException {
        Map<String, PlayerData.BadgeRecord> badges = d.badges;
        d.badges = new LinkedHashMap<>();
        String json = Json.COMPACT.toJson(d);
        d.badges = badges;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("REPLACE INTO " + p + "players (uuid,name,data) VALUES (?,?,?)")) {
                    ps.setString(1, d.uuid);
                    ps.setString(2, d.name);
                    ps.setString(3, json);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + p + "player_badges WHERE player_uuid=?")) {
                    ps.setString(1, d.uuid);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + p + "player_badges (player_uuid,badge_id,item_id,obtained_at,holder_uuid,"
                        + "holder_name,holder_types,duration_seconds,turns,challenger_team,holder_team,battle_id,note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (PlayerData.BadgeRecord b : badges.values()) {
                        ps.setString(1, d.uuid);
                        ps.setString(2, b.postId);
                        ps.setString(3, b.itemId);
                        ps.setLong(4, b.obtainedAt);
                        ps.setString(5, b.holderUuid);
                        ps.setString(6, b.holderName);
                        ps.setString(7, b.holderTypes == null ? "" : String.join(",", b.holderTypes));
                        ps.setLong(8, b.durationSeconds);
                        ps.setInt(9, b.turns);
                        ps.setString(10, Json.COMPACT.toJson(b.challengerTeam));
                        ps.setString(11, Json.COMPACT.toJson(b.holderTeam));
                        ps.setString(12, b.battleId);
                        ps.setString(13, b.note);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    @Override
    public List<UUID> listPlayers() throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + p + "players");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.add(UUID.fromString(rs.getString(1)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    @Override
    public String loadKv(String key) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT v FROM " + p + "kv WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Override
    public void saveKv(String key, String json) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("REPLACE INTO " + p + "kv (k,v) VALUES (?,?)")) {
            ps.setString(1, key);
            ps.setString(2, json);
            ps.executeUpdate();
        }
    }
}
