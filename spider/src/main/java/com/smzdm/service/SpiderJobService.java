package com.smzdm.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.smzdm.Enum.SpiderConfigEnum;
import com.smzdm.Enum.TypeRelationEnum;
import com.smzdm.mapper.*;
import com.smzdm.pojo.Article;
import com.smzdm.pojo.ArticleInfo;
import com.smzdm.pojo.ArticleJson;
import com.smzdm.pojo.Category;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import us.codecraft.webmagic.ResultItems;
import us.codecraft.webmagic.Spider;
import us.codecraft.webmagic.processor.PageProcessor;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

@Service
public class SpiderJobService {

    @Resource(name = "infoSpiderConfig")
    private PageProcessor infoSpiderConfig;
    @Resource
    private JsonConvertService jsonConvertService;
    @Resource
    private ArticleMapper articleMapper;
    @Resource
    private ArticleInfoMapper articleInfoMapper;
    @Resource
    private ArticleJsonMapper articleJsonMapper;
    @Resource
    private CategoryMapper categoryMapper;
    @Resource
    private EnumMapper enumMapper;
    @Resource(name = "longValueTemplate")
    private RedisTemplate<String, Long> longValueTemplate;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void getInfo(SpiderConfigEnum spiderConfig) {
        Spider spider = Spider.create(infoSpiderConfig);
        List<String> strings = Arrays.asList(spiderConfig.getUrl());
        List<ResultItems> resultItems = spider.getAll(strings);
        spider.close();
        JSONArray array = resultItems.stream().map(x -> (JSONArray) x.get("json")).reduce(new JSONArray(), (acc, element) -> {
            acc.addAll(element);
            return acc;
        });
        List<JSONObject> jsonList = array.stream().map(x -> (JSONObject) x).filter(x -> x.containsKey("article_id")).collect(toList());
        generateArticleInfo(jsonList);
        if (spiderConfig.getRedisKey() != null) {
            insertArticle(spiderConfig, jsonList);
        }
    }

    private void generateArticleInfo(List<JSONObject> jsonList) {
        LocalDateTime now = LocalDateTime.now();
        List<ArticleInfo> infoList = jsonList.stream().map(jsonConvertService::convertToInfo).filter(Objects::nonNull).collect(toList());
        infoList.forEach(x -> x.setUpdateTime(now));
        articleInfoMapper.deleteByIDArticleIDs(infoList.stream().map(ArticleInfo::getArticleId).collect(toList()));
        articleInfoMapper.insertHistoryList(infoList);
        articleInfoMapper.insertList(infoList);
    }

    private void insertArticle(SpiderConfigEnum spiderConfig, List<JSONObject> jsonList) {
        String key = spiderConfig.getRedisKey();
        boolean discovery = spiderConfig.isDiscovery();
        long timeSort = Optional.ofNullable(longValueTemplate.opsForValue().get(key)).orElse(0L);
        generateArticleJson(jsonList, discovery, timeSort);
        longValueTemplate.opsForValue().set(key, generateArticle(jsonList, discovery, timeSort));
    }

    private Long generateArticle(List<JSONObject> jsonList, boolean discovery, Long timeSort) {
        List<Article> articles = jsonList.stream().filter(x -> x.getLong("timesort") > timeSort).map(jsonConvertService::convertToArticle).collect(toList());
        List<Integer> ids = articles.stream().map(Article::getArticleId).collect(toList());
        articleMapper.deleteByIDList(ids);
        articles.forEach(x -> x.setIsDiscovery(discovery));
        for (TypeRelationEnum value : TypeRelationEnum.values()) {
            updateRedisType(articles, value.getKey(), value.getFunction());
        }
        if (discovery) {
            Set<Long> redisCategories = longValueTemplate.opsForSet().members("category_layer");
            Set<Category> newCategories = jsonList.stream().flatMap(x -> x.getJSONArray("category_layer").stream().map(y -> jsonConvertService.convertToCategory((JSONObject) y))).filter(z -> !redisCategories.contains(z.getId().longValue())).collect(toSet());
            if (newCategories.size() > 0) {
                Set<Long> collect = newCategories.stream().map(x -> x.getId().longValue()).collect(toSet());
                longValueTemplate.opsForSet().add("category_layer", (Long[]) collect.toArray());
                categoryMapper.insertList(newCategories);
            }
        }
        articleMapper.insertList(articles);
        return articles.stream().map(Article::getTimeSort).max(Long::compareTo).orElse(timeSort);
    }

    private void generateArticleJson(List<JSONObject> jsonList, boolean discovery, Long timeSort) {
        LocalDateTime now = LocalDateTime.now();
        List<ArticleJson> list = jsonList.stream().filter(x -> x.getLong("timesort") > timeSort).map(x -> {
            ArticleJson articleJson = new ArticleJson();
            articleJson.setContent(x.toJSONString());
            articleJson.setCreateDate(now);
            articleJson.setIsDiscovery(discovery);
            return articleJson;
        }).collect(toList());
        if (list.size() > 0) {
            articleJsonMapper.insertList(list);
        }
    }

    private void updateRedisType(List<Article> articles, String key, Function<Article, String> function) {
        Set<String> redisMembers = stringRedisTemplate.opsForSet().members(key);
        List<String> streamMembers = articles.stream().map(function).distinct().collect(toList());
        streamMembers.removeAll(redisMembers);
        if (streamMembers.size() > 0) {
            stringRedisTemplate.opsForSet().add(key, (String[]) streamMembers.toArray());
            streamMembers.forEach(x -> enumMapper.addEnum(key, "'" + x + "'"));
        }
    }
}