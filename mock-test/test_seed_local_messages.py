import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seed_local_messages as seed


class SeedLocalMessagesTest(unittest.TestCase):
    def test_build_rows_keeps_existing_friend_profiles_and_contacts_untouched(self):
        _, conversations, profiles, contacts = seed.build_rows(
            user_a="15000000000",
            friends=["15000000001"],
            per_peer=1,
            now_ms=1_700_000_000_000,
            user_a_profile=None,
        )

        self.assertEqual([], profiles)
        self.assertEqual([], contacts)
        self.assertEqual("15000000001", conversations[0][1])
        self.assertEqual("15000000001", conversations[0][2])
        self.assertEqual("15000000001", conversations[0][4])

    def test_build_rows_interleaves_user_message_then_peer_reply(self):
        messages, _, _, _ = seed.build_rows(
            user_a="15000000000",
            friends=["15000000001"],
            per_peer=2,
            now_ms=1_700_000_000_000,
            user_a_profile=None,
        )

        self.assertEqual(
            [
                ("15000000000", "15000000001", "OUTGOING", "SENT"),
                ("15000000001", "15000000000", "INCOMING", "RECEIVED"),
                ("15000000000", "15000000001", "OUTGOING", "SENT"),
                ("15000000001", "15000000000", "INCOMING", "RECEIVED"),
            ],
            [(row[4], row[5], row[23], row[22]) for row in messages],
        )

    def test_build_rows_assigns_server_seq_in_chat_timeline_order(self):
        messages, _, _, _ = seed.build_rows(
            user_a="15000000000",
            friends=["15000000001"],
            per_peer=3,
            now_ms=1_700_000_000_000,
            user_a_profile=None,
        )

        self.assertEqual([1, 2, 3, 4, 5, 6], [row[7] for row in messages])

    def test_build_rows_adds_only_user_a_profile_when_present(self):
        _, _, profiles, contacts = seed.build_rows(
            user_a="15000000000",
            friends=["15000000001"],
            per_peer=1,
            now_ms=1_700_000_000_000,
            user_a_profile=(
                "15000000000",
                "15000000000",
                "Alice",
                "https://example.com/a.jpg",
                1_780_733_364_927,
                1_780_733_364_927,
                None,
                None,
            ),
        )

        self.assertEqual(1, len(profiles))
        self.assertEqual("15000000000", profiles[0][0])
        self.assertEqual("https://example.com/a.jpg", profiles[0][3])
        self.assertEqual([], contacts)

    def test_load_user_a_profile_from_mock_user_db_reads_avatar_url(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            db_path = Path(temp_dir) / "mock-im-users.sqlite"
            con = sqlite3.connect(db_path)
            cur = con.cursor()
            cur.execute(
                """
                CREATE TABLE users (
                  phone TEXT PRIMARY KEY,
                  nickname TEXT,
                  avatar_url TEXT,
                  avatar_updated_at INTEGER NOT NULL DEFAULT 0,
                  updated_at INTEGER NOT NULL DEFAULT 0,
                  created_at INTEGER NOT NULL
                )
                """
            )
            cur.execute(
                """
                INSERT INTO users(phone, nickname, avatar_url, avatar_updated_at, updated_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    "15000000000",
                    "Alice",
                    "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/15000000000/1780733364927.jpg",
                    1_780_733_364_927,
                    1_780_733_364_927,
                    1_700_000_000_000,
                ),
            )
            con.commit()
            con.close()

            profile = seed.load_user_a_profile_from_mock_user_db("15000000000", db_path)

        self.assertEqual(
            (
                "15000000000",
                "15000000000",
                "Alice",
                "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/15000000000/1780733364927.jpg",
                1_780_733_364_927,
                1_780_733_364_927,
                None,
                None,
            ),
            profile,
        )

    def test_validate_seed_request_rejects_non_positive_per_peer(self):
        with self.assertRaisesRegex(ValueError, "--per-peer"):
            seed.validate_seed_request(
                user_a="15000000000",
                start=15000000000,
                end=15000000001,
                per_peer=0,
            )

    def test_validate_seed_request_rejects_empty_friend_range(self):
        with self.assertRaisesRegex(ValueError, "至少需要 1 个好友"):
            seed.validate_seed_request(
                user_a="15000000000",
                start=15000000000,
                end=15000000000,
                per_peer=1,
            )


if __name__ == "__main__":
    unittest.main()
