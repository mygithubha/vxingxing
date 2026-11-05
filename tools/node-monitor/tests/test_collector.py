import unittest
import base64
from datetime import date, datetime
from zoneinfo import ZoneInfo

from collector import default_target_date, extract_public_subscriptions, find_exact_article, probe_subscription


class CollectorTests(unittest.TestCase):
    def test_exact_date_article_only(self):
        html = """
        <a href="/2198.html">2026年11月5日免费节点</a>
        <a href="/2197.html">2026年11月4日免费节点</a>
        <a href="/2196.html">2026年11月3日免费节点</a>
        """
        article = find_exact_article(html, date(2026, 11, 4))
        self.assertIsNotNone(article)
        self.assertEqual(article.url, "https://www.yudou789.top/2197.html")

    def test_does_not_fallback_to_neighboring_date(self):
        html = '<a href="/2198.html">2026年11月5日免费节点</a>'
        self.assertIsNone(find_exact_article(html, date(2026, 11, 4)))

    def test_extracts_only_expected_host_date_and_extensions(self):
        html = """
        https://www.yudou89.shop/202611/20261104abc.txt
        https://www.yudou89.shop/202611/20261104def.yaml
        https://evil.example/20261104bad.txt
        https://www.yudou89.shop/202611/20261103old.txt
        http://www.yudou89.shop/202611/20261104insecure.txt
        """
        result = extract_public_subscriptions(html, date(2026, 11, 4))
        self.assertEqual([item.kind for item in result], ["txt", "yaml"])
        self.assertTrue(all("20261104" in item.url for item in result))

    def test_txt_subscription_probe_decodes_base64_and_counts_nodes(self):
        encoded = base64.b64encode(b"vless://one\nvmess://two").decode("ascii")
        item = extract_public_subscriptions(
            f"https://www.yudou89.shop/202611/20261104abc.txt",
            date(2026, 11, 4),
        )[0]
        checked = probe_subscription(item, fetcher=lambda _: encoded.encode("ascii"))
        self.assertTrue(checked.valid)
        self.assertEqual(checked.node_count, 2)

    def test_yaml_subscription_probe_counts_proxy_entries(self):
        item = extract_public_subscriptions(
            "https://www.yudou89.shop/202611/20261104abc.yaml",
            date(2026, 11, 4),
        )[0]
        payload = b"proxies:\n  - name: node-one\n  - name: node-two\n"
        checked = probe_subscription(item, fetcher=lambda _: payload)
        self.assertTrue(checked.valid)
        self.assertEqual(checked.node_count, 2)

    def test_default_target_is_china_date_minus_two_days(self):
        now = datetime(2026, 11, 6, 7, 30, tzinfo=ZoneInfo("Asia/Shanghai"))
        self.assertEqual(default_target_date(now), date(2026, 11, 4))


if __name__ == "__main__":
    unittest.main()
