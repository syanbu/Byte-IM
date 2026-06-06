import sys
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
        )

        self.assertEqual([1, 2, 3, 4, 5, 6], [row[7] for row in messages])

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
