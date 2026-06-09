#!/usr/bin/env python3
"""
向 Android 本地 IM 数据库 self_hosted_im.db 直接灌入测试数据。
仅写 TEXT 类型，每个好友插 OUTGOING/INCOMING 各 N 条；同步刷新 conversations
列表（last_message_preview、未读数）。

好友资料和联系人关系请由 mock-server/seed-mock-friends.ps1 灌入；本脚本不会写
friend_contacts，也不会覆盖好友的 user_profiles，但会按需把 user_a 自己的头像资料
从 mock-server/data/mock-im-users.sqlite 同步进本地库，确保 OUTGOING 气泡能显示头像。

不做 git 操作、不改 app 代码；需要 adb 通路（debug 包走 run-as；模拟器/root 走 su）。

用法：
    python mock-test/seed_local_messages.py --user-a 15000000000 --start 15000000002 --end 15000000499 --per-peer 100
    python mock-test/seed_local_messages.py --user-a 15000000000 --start 15000000002 --end 15000000499 --per-peer 100 --device 192.168.137.76:42595
"""

import argparse
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import time
from typing import List, Optional, Tuple

PKG = "com.buyansong.im"
# DB 文件名按登录账号作用域命名（见 AccountScopedDatabaseName.forUser）：
# 规则：trim → 非 [A-Za-z0-9_] 替换为 _ → 空则 "unknown" → 前缀 self_hosted_im_ / 后缀 .db
# 用 --user-a 决定实际路径；具体值在 main() 里根据 args 解析后填入。
DB_DIR = f"/data/data/{PKG}/databases"
DB_STAGE = "/data/local/tmp/_im_seed.db"
DB_LOCAL = "./_im_seed.db"

DEFAULT_USER_A = "15000000000"
DEFAULT_START = 15000000000
DEFAULT_END   = 15000000999      # 含 999；
DEFAULT_PER_PEER = 100
DEFAULT_MOCK_USER_DB = (
    Path(__file__).resolve().parent.parent / "mock-server" / "data" / "mock-im-users.sqlite"
)

OUT_STATUS = "SENT"
IN_STATUS = "RECEIVED"
OUT_DIRECTION = "OUTGOING"
IN_DIRECTION = "INCOMING"
MSG_TYPE = "TEXT"
CONV_TYPE = "SINGLE"


# ---------- adb 工具 ----------

# 全局选定的设备序列号；所有 adb 调用都通过 -s 走同一台，避免多设备歧义
DEVICE_SERIAL: str = ""


def adb(*args, check=True, capture=True) -> str:
    cmd = ["adb"]
    if DEVICE_SERIAL:
        cmd += ["-s", DEVICE_SERIAL]
    cmd += list(args)
    r = subprocess.run(cmd, capture_output=capture, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(cmd[1:])} failed: {r.stderr.strip()}")
    return r.stdout.strip() if capture else ""


def list_devices() -> List[str]:
    """返回所有处于 device 状态（非 offline/unauthorized）的序列号。"""
    r = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    out = []
    for line in r.stdout.splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            out.append(parts[0])
    return out


def pick_device(requested: str = "") -> str:
    """
    解析 --device：
      - 指定了序列号：直接用（仍会校验在 devices 列表里）
      - 未指定：选第一台 device 状态设备
    多于 1 台时给出提示，让用户用 --device 明确指定。
    """
    available = list_devices()
    if not available:
        raise RuntimeError("adb 不可用或无处于 device 状态的设备")
    if requested:
        if requested not in available:
            raise RuntimeError(
                f"指定的设备 {requested!r} 不在已连接列表中：{available}"
            )
        return requested
    if len(available) > 1:
        print(f"[info] 检测到多台设备 {available}；默认使用 {available[0]}。"
              f"如需切换请加 --device <serial>")
    return available[0]


def device_has_root() -> bool:
    return subprocess.run(
        ["adb", "-s", DEVICE_SERIAL, "shell", "su", "-c", "true"],
        capture_output=True,
    ).returncode == 0


def safe_user_id_for(user_id: str) -> str:
    """镜像 AccountScopedDatabaseName.forUser 的清洗规则。"""
    s = user_id.strip()
    s = re.sub(r"[^A-Za-z0-9_]", "_", s)
    return s or "unknown"


def stop_app() -> None:
    adb("shell", f"am force-stop {PKG}")


def start_app() -> None:
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", check=False)


def list_remote_dbs() -> List[str]:
    """列出设备 databases/ 下所有 self_hosted_im_*.db 文件，便于排错。"""
    out = adb("shell", f"run-as {PKG} ls {DB_DIR}", check=False)
    return [ln.strip() for ln in out.splitlines()
            if ln.strip().startswith("self_hosted_im_") and ln.strip().endswith(".db")]


def pull_db(user_a: str) -> None:
    db_remote = f"{DB_DIR}/self_hosted_im_{safe_user_id_for(user_a)}.db"
    if os.path.exists(DB_LOCAL):
        os.remove(DB_LOCAL)

    # 提前校验文件存在；run-as cat 失败信息会混淆权限/路径问题，分开看更清楚
    pre = adb("shell", f"run-as {PKG} sh -c 'test -f {db_remote} && echo OK || echo MISS'",
              check=False)
    if "OK" not in pre:
        existing = list_remote_dbs()
        hint = f"现有: {existing}" if existing else "databases/ 下无 self_hosted_im_*.db"
        raise RuntimeError(
            f"设备 {DEVICE_SERIAL} 上找不到 {db_remote}；{hint}。"
            f"请确认 --user-a 与已登录账号一致，或先用 --device 切到正确的设备。"
        )

    if device_has_root():
        adb("shell", f"su -c 'cat {db_remote} > {DB_STAGE} && chmod 666 {DB_STAGE}'")
    else:
        # debug 包：run-as 出来的进程（app uid）写不进 /data/local/tmp/，
        # 所以把 > 重定向交给外层 shell 处理 —— shell 用户拥有 /data/local/tmp/，
        # 可以正常 open/truncate；run-as cat 负责读 DB，二者通过管道串起来。
        adb("shell", f"run-as {PKG} cat {db_remote} > {DB_STAGE}")
        adb("shell", f"chmod 666 {DB_STAGE}", check=False)
    adb("pull", DB_STAGE, DB_LOCAL)


def push_db(user_a: str) -> None:
    db_remote = f"{DB_DIR}/self_hosted_im_{safe_user_id_for(user_a)}.db"
    adb("push", DB_LOCAL, DB_STAGE)
    if device_has_root():
        adb("shell", f"su -c 'cat {DB_STAGE} > {db_remote} && chmod 660 {db_remote}'")
    else:
        # 同理：外层 shell 读 /data/local/tmp/，通过管道喂给 run-as 进程，
        # 由 app uid 写入自己的沙箱 —— 内部 > 由 run-as 起的 sh -c 处理。
        adb("shell", f"run-as {PKG} sh -c 'cat {DB_STAGE} > {db_remote}'")


# ---------- 数据生成 ----------

def conversation_id(a: str, b: str) -> str:
    p = sorted([a, b])
    return f"single:{p[0]}:{p[1]}"


def load_user_a_profile_from_mock_user_db(
    user_a: str, mock_user_db: Path
) -> Optional[Tuple[str, str, str, Optional[str], int, int, None, None]]:
    con = sqlite3.connect(mock_user_db)
    try:
        cur = con.cursor()
        cur.execute(
            """
            SELECT phone, nickname, avatar_url, avatar_updated_at, updated_at, created_at
            FROM users
            WHERE phone = ?
            """,
            (user_a,),
        )
        row = cur.fetchone()
    finally:
        con.close()

    if row is None:
        return None

    phone, nickname, avatar_url, avatar_updated_at, updated_at, created_at = row
    resolved_nickname = nickname.strip() if isinstance(nickname, str) and nickname.strip() else phone
    resolved_updated_at = updated_at or created_at or 0
    return (
        phone,
        phone,
        resolved_nickname,
        avatar_url,
        avatar_updated_at or 0,
        resolved_updated_at,
        None,
        None,
    )


def build_rows(
    user_a: str,
    friends: List[str],
    per_peer: int,
    now_ms: int,
    user_a_profile: Optional[Tuple[str, str, str, Optional[str], int, int, None, None]] = None,
) -> Tuple[List[Tuple], List[Tuple], List[Tuple], List[Tuple]]:
    messages: List[Tuple] = []
    conversations: List[Tuple] = []
    profiles: List[Tuple] = []
    contacts: List[Tuple] = []

    if user_a_profile is not None:
        profiles.append(user_a_profile)

    for idx, peer in enumerate(friends):
        conv_id = conversation_id(user_a, peer)
        # 基准时间：每个好友整体往后错开 1 秒，避免最后一条预览全部撞同一毫秒
        base_t = now_ms - (len(friends) - idx) * 60_000

        last_msg_id, last_preview, last_time = None, None, None

        for k in range(per_peer):
            t = base_t + k * 1000  # 每条 1s 间隔

            # user_a → peer（OUTGOING）
            mid_out = f"seed-out-{peer}-{k}"
            content_out = f"[{user_a[-3:]}→{peer[-3:]}] 这是 A 发送的第 {k+1} 条消息"
            messages.append((
                mid_out, conv_id, CONV_TYPE, None,
                user_a, peer,
                k * 2, k * 2 + 1,            # client_seq, server_seq
                content_out, None, MSG_TYPE,
                None, None, None, None, None, None, None, None,
                0, None, None,
                OUT_STATUS, OUT_DIRECTION,
                t, t,
            ))
            last_msg_id, last_preview, last_time = mid_out, content_out, t

            # peer → user_a（INCOMING），时间略晚于同序号的 OUT
            mid_in = f"seed-in-{peer}-{k}"
            t_in = t + 500
            content_in = f"[{peer[-3:]}→{user_a[-3:]}] 已收到第 {k+1} 条，回复一下"
            messages.append((
                mid_in, conv_id, CONV_TYPE, None,
                peer, user_a,
                k * 2 + 1, k * 2 + 2,
                content_in, None, MSG_TYPE,
                None, None, None, None, None, None, None, None,
                0, None, None,
                IN_STATUS, IN_DIRECTION,
                t_in, t_in,
            ))
            last_msg_id, last_preview, last_time = mid_in, content_in, t_in

        # 每条 INCOMING 算 1 条未读，UI 上能看到红点
        unread = per_peer
        # peer_name/title 只是会话兜底显示名；真实昵称由已灌入的 user_profiles 提供。
        peer_name = peer
        conversations.append((
            conv_id, peer, peer_name, CONV_TYPE, peer_name, None,
            last_msg_id, last_preview, last_time,
            unread, 0,                       # unread_count, mention_unread_count
            last_time, None, None,          # peer_read_* 留空
        ))

    return messages, conversations, profiles, contacts


def validate_seed_request(user_a: str, start: int, end: int, per_peer: int) -> List[str]:
    if end < start:
        raise ValueError(f"--end ({end}) 必须 >= --start ({start})")
    if per_peer <= 0:
        raise ValueError(f"--per-peer ({per_peer}) 必须 > 0")

    width = max(len(str(start)), len(str(end)))
    candidates = [str(i).zfill(width) for i in range(start, end + 1)]
    friends = [uid for uid in candidates if uid != user_a]
    if not friends:
        raise ValueError("至少需要 1 个好友；请扩大 --start..--end 或调整 --user-a")
    return friends


# ---------- 写库 ----------

def clear_existing(cur: sqlite3.Cursor, user_a: str, friends: List[str]) -> None:
    cur.execute("PRAGMA foreign_keys = OFF")
    cur.execute("BEGIN")
    for peer in friends:
        cur.execute("DELETE FROM messages WHERE sender_id IN (?, ?) AND receiver_id IN (?, ?)",
                    (user_a, peer, user_a, peer))
        cur.execute("DELETE FROM conversations WHERE peer_id = ?", (peer,))
    cur.execute("COMMIT")


def insert_all(cur: sqlite3.Cursor, messages, conversations, profiles, contacts) -> None:
    cur.executemany(
        """INSERT OR REPLACE INTO messages(
            message_id, conversation_id, conversation_type, group_id,
            sender_id, receiver_id, client_seq, server_seq,
            content, mentions_json, message_type,
            image_url, thumbnail_url, image_width, image_height,
            mime_type, file_size_bytes, local_original_path, local_thumbnail_path,
            is_recalled, recalled_at, recalled_by,
            status, direction, created_at, updated_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        messages,
    )
    cur.executemany(
        """INSERT OR REPLACE INTO conversations(
            conversation_id, peer_id, peer_name, conversation_type, title, avatar_url,
            last_message_id, last_message_preview, last_message_time,
            unread_count, mention_unread_count, updated_at,
            peer_read_up_to_server_seq, peer_read_at
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        conversations,
    )
    cur.executemany(
        """INSERT OR REPLACE INTO user_profiles(
            user_id, phone, nickname, avatar_url, avatar_updated_at, updated_at, gender, signature
        ) VALUES (?,?,?,?,?,?,?,?)""",
        profiles,
    )


# ---------- main ----------

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Seed Android IM local SQLite with synthetic messages.")
    p.add_argument("--user-a", default=DEFAULT_USER_A, help="登录用户 A 的 userId（若落在 --start..--end 范围，会自动从好友里剔除）")
    p.add_argument("--start", type=int, default=DEFAULT_START, help="候选 ID 起始（含）")
    p.add_argument("--end", type=int, default=DEFAULT_END, help="候选 ID 结束（含）")
    p.add_argument("--per-peer", type=int, default=DEFAULT_PER_PEER, help="每个好友的消息数（OUT/IN 各一半）")
    p.add_argument("--mock-user-db", default=str(DEFAULT_MOCK_USER_DB), help="mock-server 用户库路径；用于补齐 user_a 的本地头像资料")
    p.add_argument("--keep-local", action="store_true", help="保留本地暂存 DB 文件")
    p.add_argument("--no-restart", action="store_true", help="写完不重启 app")
    p.add_argument("--device", default="", help="目标设备序列号（adb devices 第一列）；多设备时建议显式指定")
    return p.parse_args()


def main() -> int:
    args = parse_args()

    try:
        friends = validate_seed_request(args.user_a, args.start, args.end, args.per_peer)
    except ValueError as e:
        print(str(e), file=sys.stderr)
        return 2
    width = max(len(str(args.start)), len(str(args.end)))
    candidates = [str(i).zfill(width) for i in range(args.start, args.end + 1)]
    if len(friends) == len(candidates):
        print(f"[info] user_a={args.user_a} 不在 --start..--end 范围；好友 = 全部 {len(friends)} 个")
    else:
        print(f"[info] user_a={args.user_a} 已从候选范围剔除；好友 = {len(friends)} 个")
    print(f"[plan] user_a={args.user_a} friends={len(friends)} per_peer={args.per_peer} "
          f"total_messages={len(friends) * args.per_peer * 2}")

    try:
        global DEVICE_SERIAL
        DEVICE_SERIAL = pick_device(args.device)
    except RuntimeError as e:
        print(str(e), file=sys.stderr)
        return 3
    print(f"[device] serial={DEVICE_SERIAL}")

    print("[1/5] 关闭 app（释放 SQLite 锁）")
    stop_app()

    print("[2/5] 拉取数据库到本地")
    pull_db(args.user_a)

    print("[3/5] 清理目标好友的旧数据并插入新数据")
    con = sqlite3.connect(DB_LOCAL)
    con.row_factory = None
    cur = con.cursor()
    clear_existing(cur, args.user_a, friends)
    now_ms = int(time.time() * 1000)
    user_a_profile = None
    mock_user_db = Path(args.mock_user_db)
    if mock_user_db.exists():
        user_a_profile = load_user_a_profile_from_mock_user_db(args.user_a, mock_user_db)
        if user_a_profile is None:
            print(f"[warn] mock 用户库未找到 {args.user_a}；跳过补齐 user_a 本地头像")
    else:
        print(f"[warn] mock 用户库不存在：{mock_user_db}；跳过补齐 user_a 本地头像")
    messages, conversations, profiles, contacts = build_rows(
        args.user_a, friends, args.per_peer, now_ms, user_a_profile=user_a_profile
    )
    insert_all(cur, messages, conversations, profiles, contacts)
    con.commit()
    con.close()
    print(f"      messages={len(messages)} conversations={len(conversations)} "
          f"profiles={len(profiles)} contacts={len(contacts)}")

    print("[4/5] 推送回设备")
    push_db(args.user_a)

    if not args.no_restart:
        print("[5/5] 启动 app")
        start_app()
    else:
        print("[5/5] 跳过重启（--no-restart）")

    if not args.keep_local:
        try:
            os.remove(DB_LOCAL)
        except OSError:
            pass

    print("完成。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
