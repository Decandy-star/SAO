package com.sao.fakeserver.session;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionHub {
    private final ConcurrentHashMap<String, GameSession> byAccount = new ConcurrentHashMap<>();

    public void bind(GameSession session) {
        if (session == null || session.account() == null || session.account().isEmpty()) {
            return;
        }
        byAccount.put(session.account(), session);
    }

    public void unbind(GameSession session) {
        if (session == null || session.account() == null) {
            return;
        }
        byAccount.remove(session.account(), session);
    }

    public GameSession get(String account) {
        return account == null ? null : byAccount.get(account);
    }

    /** 断开指定账号连接并从 hub 移除；未在线返回 0。 */
    public int disconnect(String account) {
        if (account == null || account.isEmpty()) {
            return 0;
        }
        GameSession s = byAccount.remove(account);
        if (s != null && s.channel() != null && s.channel().isActive()) {
            s.channel().close();
            return 1;
        }
        return 0;
    }

    /** 断开全部在线连接并从 hub 移除。 */
    public int disconnectAll() {
        int n = 0;
        for (GameSession s : byAccount.values()) {
            if (s != null && s.channel() != null && s.channel().isActive()) {
                s.channel().close();
                n++;
            }
        }
        byAccount.clear();
        return n;
    }

    /** 当前在线且 channel 仍活着的会话快照（调度推包用）。 */
    public java.util.List<GameSession> onlineSnapshot() {
        java.util.ArrayList<GameSession> out = new java.util.ArrayList<>();
        for (GameSession s : byAccount.values()) {
            if (s != null && s.channel() != null && s.channel().isActive() && s.player() != null) {
                out.add(s);
            }
        }
        return out;
    }
}
