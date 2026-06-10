from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class Classification:
    category: str
    region: str
    score: int


CATEGORY_KEYWORDS: dict[str, tuple[str, ...]] = {
    "时政": (
        "中国",
        "国务院",
        "北京",
        "上海",
        "广东",
        "深圳",
        "香港",
        "澳门",
        "台湾",
        "全国",
        "省",
        "市",
        "县",
        "经济",
        "政策",
    ),
    "国际": (
        "美国",
        "欧洲",
        "英国",
        "法国",
        "德国",
        "日本",
        "韩国",
        "俄罗斯",
        "乌克兰",
        "联合国",
        "中东",
        "全球",
        "国际",
    ),
    "科技": (
        "AI",
        "人工智能",
        "芯片",
        "手机",
        "互联网",
        "算法",
        "机器人",
        "卫星",
        "航天",
        "数据",
        "模型",
    ),
    "财经": (
        "股票",
        "股市",
        "基金",
        "银行",
        "央行",
        "汇率",
        "上市",
        "财报",
        "投资",
        "消费",
        "房地产",
    ),
    "体育": (
        "比赛",
        "冠军",
        "世界杯",
        "奥运",
        "联赛",
        "足球",
        "篮球",
        "网球",
        "运动员",
    ),
    "娱乐": (
        "电影",
        "电视剧",
        "综艺",
        "演员",
        "明星",
        "票房",
        "音乐",
        "演唱会",
    ),
}

DOMESTIC_HINTS = (
    "中国",
    "国内",
    "北京",
    "上海",
    "广东",
    "深圳",
    "香港",
    "澳门",
    "台湾",
    "新华社",
    "人民网",
    "央视",
)

OVERSEAS_HINTS = (
    "国际",
    "海外",
    "全球",
    "美国",
    "欧洲",
    "英国",
    "法国",
    "德国",
    "日本",
    "韩国",
    "俄罗斯",
    "联合国",
    "BBC",
    "Reuters",
    "AP",
)


def classify(title: str, description: str = "", source_name: str = "") -> Classification:
    text = _normalize(" ".join([title, description, source_name]))
    category_scores = {
        category: sum(_count_keyword(text, keyword) for keyword in keywords)
        for category, keywords in CATEGORY_KEYWORDS.items()
    }
    topical_scores = {
        category: score
        for category, score in category_scores.items()
        if category not in {"时政", "国际"}
    }
    topical_category, topical_score = max(topical_scores.items(), key=lambda item: item[1])
    broad_category, broad_score = max(category_scores.items(), key=lambda item: item[1])
    if topical_score > 0:
        category = topical_category
        category_score = topical_score
    elif broad_score > 0:
        category = broad_category
        category_score = broad_score
    else:
        category = "综合"
        category_score = 0

    domestic_score = sum(_count_keyword(text, keyword) for keyword in DOMESTIC_HINTS)
    overseas_score = sum(_count_keyword(text, keyword) for keyword in OVERSEAS_HINTS)
    region = "境内" if domestic_score >= overseas_score else "境外"
    if domestic_score == 0 and overseas_score == 0:
        region = "境外" if category == "国际" else "境内"

    return Classification(category=category, region=region, score=category_score + max(domestic_score, overseas_score))


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def _count_keyword(text: str, keyword: str) -> int:
    if keyword.isascii():
        return len(re.findall(re.escape(keyword), text, flags=re.IGNORECASE))
    return text.count(keyword)
