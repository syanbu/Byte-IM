import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seed_local_messages as seed


class SeedLocalMessagesTest(unittest.TestCase):
    def test_build_rows_uses_app_supported_message_statuses(self):
        messages, _, _, _ = seed.build_rows(
            user_a="15000000000",
            friends=["15000000001"],
            per_peer=1,
            now_ms=1_700_000_000_000,
        )

        statuses = {row[22] for row in messages}

        self.assertEqual({"SENT", "RECEIVED"}, statuses)

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
