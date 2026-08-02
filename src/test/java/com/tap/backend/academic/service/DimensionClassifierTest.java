package com.tap.backend.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DimensionClassifierTest {

    @Test
    void classifiesByExperimentNameKeywords() {
        assertEquals("线性表", DimensionClassifier.classify("第3次实验(单链表)"));
        assertEquals("栈与队列", DimensionClassifier.classify("栈的应用"));
        assertEquals("树", DimensionClassifier.classify("二叉树遍历"));
        assertEquals("图", DimensionClassifier.classify("DFS/BFS"));
        assertEquals("哈希", DimensionClassifier.classify("HashTable 实现"));
    }

    @Test
    void graphCompoundsBeatTreeRule() {
        // Regression: "最小生成树" 以前被"树"规则误吞
        assertEquals("图", DimensionClassifier.classify("最小生成树"));
        assertEquals("图", DimensionClassifier.classify("Kruskal 最小生成树"));
        assertEquals("图", DimensionClassifier.classify("Dijkstra 最短路径"));
        assertEquals("图", DimensionClassifier.classify("拓扑排序"));
    }

    @Test
    void englishPatternsAreWholeWordNotLooseSubstring() {
        // Regression: bare "list"/"map" 子串匹配会把 "Adjacency List Graph" 误判为线性表
        assertEquals("图", DimensionClassifier.classify("Adjacency List Graph"));
        assertEquals("线性表", DimensionClassifier.classify("LinkedList 实现"));
    }

    @Test
    void classifyOfferingPicksSingleDimensionFromKnowledgeLeaves() {
        assertEquals("树", DimensionClassifier.classifyOffering("第6次作业", "二叉搜索树;BST"));
        assertEquals("图", DimensionClassifier.classifyOffering("综合实验", "Dijkstra;最短路径"));
        assertEquals("哈希", DimensionClassifier.classifyOffering("练习", "哈希表;散列"));
    }

    @Test
    void classifyOfferingReturnsGeneralWhenKnowledgeSpansMultipleDimensions() {
        // 多维知识点 → 综合，而不是任意取第一个
        assertEquals("综合", DimensionClassifier.classifyOffering("综合实验", "链表;二叉树;图"));
    }

    @Test
    void classifyOfferingFallsBackToExperimentNameWhenKnowledgeMissing() {
        assertEquals("栈与队列", DimensionClassifier.classifyOffering("栈的应用", ""));
        assertEquals("栈与队列", DimensionClassifier.classifyOffering("栈的应用", null));
    }

    @Test
    void classifyOfferingReturnsUnclassifiedWhenNoSignal() {
        assertEquals(DimensionClassifier.UNCLASSIFIED, DimensionClassifier.classifyOffering("开学第一课", ""));
        assertEquals(DimensionClassifier.UNCLASSIFIED, DimensionClassifier.classifyOffering("", ""));
    }

    @Test
    void classifyByPathExtractsRealChapterAcrossCourses() {
        assertEquals("线性表", DimensionClassifier.classifyByPath(
                "数据结构/线性表/单链表", "单链表", "实验"));
        assertEquals("指针", DimensionClassifier.classifyByPath(
                "C语言/指针/指针运算", "指针运算", "实验"));
        assertEquals("函数", DimensionClassifier.classifyByPath(
                "计算机/C语言/函数/递归", "递归", "实验"));
        assertEquals("数组", DimensionClassifier.classifyByPath(
                "C语言/数组", "数组", "实验"));
    }

    @Test
    void classifyByPathUsesMajorityForMultipleKnowledgePaths() {
        assertEquals("线性表", DimensionClassifier.classifyByPath(
                "数据结构/线性表/单链表;数据结构/树/二叉树;数据结构/线性表/顺序表",
                "单链表;二叉树;顺序表", "综合实验"));
    }

    @Test
    void classifyByPathFallsBackWhenPathMissing() {
        assertEquals("栈与队列", DimensionClassifier.classifyByPath(
                "", "栈;队列", "实验"));
    }

    @Test
    void classifyFallsBackToGeneralWhenNoKeyword() {
        assertEquals(DimensionClassifier.FALLBACK_DIMENSION, DimensionClassifier.classify("开学第一课"));
        assertEquals(DimensionClassifier.FALLBACK_DIMENSION, DimensionClassifier.classify(""));
        assertEquals(DimensionClassifier.FALLBACK_DIMENSION, DimensionClassifier.classify((CharSequence) null));
    }
}
