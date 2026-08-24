package com.star.crawl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ContentExtractor 测试：导航(链接多密度低) vs 正文(文字多密度高)
class ContentExtractorTest {

    private final ContentExtractor extractor = new ContentExtractor();

    // 导航/菜单链接多 → 密度低被丢；正文段落密度高被选中
    @Test
    void picksArticleOverNav() {
        String html = "<html><body>"
                + "<nav><a href='/a'>首页</a><a href='/b'>北京租房</a><a href='/c'>上海租房</a>"
                + "<a href='/d'>广州租房</a><a href='/e'>杭州租房</a><a href='/f'>关于我们</a></nav>"
                + "<div class='content'><p>北京互联网公司集中在西二旗和后厂村，中关村科技园聚集大量科技企业。"
                + "这里通勤便利，两居室月租普遍在六千元以上，适合互联网从业者居住。</p></div>"
                + "</body></html>";

        String main = extractor.extractMainText(html);

        assertTrue(main.contains("西二旗"), "应提取正文段落，实际: " + main);
        assertFalse(main.contains("首页"), "导航链接不应混进正文");
        assertFalse(main.contains("北京租房"), "导航链接不应混进正文");
    }

    // 脚本/样式内容剥掉，不污染正文
    @Test
    void stripsScriptsAndStyles() {
        String html = "<html><body>"
                + "<script>var secret = '动态加载的暗桩文字';</script>"
                + "<style>.hidden { display: none; }</style>"
                + "<p>这是一段真实的正文内容，讲的是租房通勤的成本分析，足够长超过最小长度阈值。</p>"
                + "</body></html>";

        String main = extractor.extractMainText(html);

        assertTrue(main.contains("租房通勤"));
        assertFalse(main.contains("暗桩文字"), "script 内容不应进正文");
        assertFalse(main.contains("display"), "style 内容不应进正文");
    }

    // 无正文（全是导航）→ 返回空串，调用方降级
    @Test
    void navOnly_returnsEmpty() {
        String html = "<nav><a href='/1'>链接一</a><a href='/2'>链接二</a><a href='/3'>链接三</a>"
                + "<a href='/4'>链接四</a><a href='/5'>链接五</a></nav>";

        assertEquals("", extractor.extractMainText(html));
    }

    // 实体转义密集的块（&lt;style&gt; 墙）跳过：真实页面把 CSS 编码成文本时的坑（百度首页实测暴露）
    @Test
    void entityEncodedMarkup_skipped() {
        String html = "<div>&lt;style data-for=\"result\" type=\"text/css\"&gt;html{font-size:100px}"
                + "body{color:#333;background:#fff;padding:0;margin:0}&lt;/style&gt;</div>"
                + "<div>这是真正的一段正文内容，讲的是租房通勤和房价分析，文字足够长超过最小长度阈值，值得被选中。</div>";

        String main = extractor.extractMainText(html);

        assertFalse(main.contains("font-size"), "实体转义的样式墙不应进正文");
        assertTrue(main.contains("租房通勤"), "正文段落应被选中");
    }
}