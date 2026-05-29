package com.tap.backend.academic.service.impl;

import com.tap.backend.academic.dao.AISuggestedProblemDao;
import com.tap.backend.academic.entity.AISuggestedProblem;
import com.tap.backend.academic.service.AISuggestedProblemService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AISuggestedProblemServiceImpl implements AISuggestedProblemService {

    @Autowired
    private AISuggestedProblemDao aiSuggestedProblemDao;

    @Override
    public AISuggestedProblem findByStudentIdAndExperimentId(int studentId, int experimentId) {
        System.out.println("查询学生ID: " + studentId + ", 实验ID: " + experimentId + "的推荐练习");
        AISuggestedProblem result = aiSuggestedProblemDao.findByStudentIdAndExperimentId(studentId, experimentId);
        if (result != null) {
            System.out.println("查询结果: " + result);
        } else {
            System.out.println("未找到推荐练习");
        }
        return result;
    }

    @Override
    public List<AISuggestedProblem> findByStudentId(int studentId) {
        System.out.println("查询学生ID: " + studentId + "的所有推荐练习");
        List<AISuggestedProblem> results = aiSuggestedProblemDao.findByStudentId(studentId);
        System.out.println("查询结果数量: " + (results != null ? results.size() : 0));
        if (results != null && !results.isEmpty()) {
            for (AISuggestedProblem problem : results) {
                System.out.println("推荐练习123: " + problem);
            }
        }
        return results;
    }

//    @Override
//    public List<Map<String, Object>> parseRecommendedPractices(String content) {
//        List<Map<String, Object>> recommendedPractices = new ArrayList<>();
//
//        if (content == null || content.isEmpty()) {
//            return recommendedPractices;
//        }
//
//        try {
//            // 提取推荐题目部分
//            Pattern titlePattern = Pattern.compile("## 推荐题目：(.+?)\\n");
//            Matcher titleMatcher = titlePattern.matcher(content);
//
//            // 提取题目要求部分
//            Pattern requirementPattern = Pattern.compile("题目要求：([\\s\\S]+?)(?=详细解析)", Pattern.DOTALL);
//            Matcher requirementMatcher = requirementPattern.matcher(content);
//            System.out.println("解析结果: " + requirementMatcher.find());
//
//            if(!requirementMatcher.find()){
//                requirementPattern = Pattern.compile("题目内容：([\\s\\S]+?)(?=题目解析)", Pattern.DOTALL);
//                requirementMatcher = requirementPattern.matcher(content);
//            }
//
//            // 提取详细解析部分
//            Pattern analysisPattern = Pattern.compile("详细解析([\\s\\S]+)", Pattern.DOTALL);
//            Matcher analysisMatcher = analysisPattern.matcher(content);
//            if(!analysisMatcher.find()){
//                analysisPattern = Pattern.compile("题目解析([\\s\\S]+)", Pattern.DOTALL);
//                analysisMatcher = analysisPattern.matcher(content);
//            }
//
//
////            // 提取测试用例部分
////            Pattern testCasePattern = Pattern.compile("### 测试用例([\\s\\S]+)", Pattern.DOTALL);
////            Matcher testCaseMatcher = testCasePattern.matcher(content);
//
//
//            if (titleMatcher.find()) {
//                Map<String, Object> practice = new HashMap<>();
//                practice.put("title", titleMatcher.group(1).trim());
//                System.out.println("推荐练习标题: " + practice.get("title"));
//                if (requirementMatcher.find()) {
//                    practice.put("description", requirementMatcher.group(1).trim());
//                    System.out.println("推荐练习描述: " + practice.get("description"));
//                } else {
//                    practice.put("description", "详细要求见题目内容");
//                    System.out.println("推荐练习描述: " + practice.get("description"));
//                }
//
//                if (analysisMatcher.find()) {
//                    practice.put("analysis", analysisMatcher.group(1).trim());
//                    System.out.println("推荐练习解析: " + practice.get("analysis"));
//                }
//
////                if (testCaseMatcher.find()) {
////                    practice.put("testCase", testCaseMatcher.group(1).trim());
////                    System.out.println("推荐练习测试用例: " + practice.get("testCase"));
////                }
//
//                recommendedPractices.add(practice);
//            }
//
//        } catch (Exception e) {
//            System.out.println("解析推荐练习内容时出错: " + e.getMessage());
//            e.printStackTrace();
//        }
//
//        return recommendedPractices;
//    }

    @Override
    public List<Map<String, Object>> parseRecommendedPractices(String content) {
        List<Map<String, Object>> recommendedPractices = new ArrayList<>();

        if (content == null || content.isEmpty()) {
            return recommendedPractices;
        }

        try {
            // 找出介绍部分和题目列表部分
            String introduction = "";
            String problemsSection = content;
            
            // 尝试提取介绍部分(从开头到第一个编号题目前)
            Pattern introPattern = Pattern.compile("^(.*?)(?=\\d+\\. )", Pattern.DOTALL);
            Matcher introMatcher = introPattern.matcher(content);
            if (introMatcher.find()) {
                introduction = introMatcher.group(1).trim();
                // 将介绍部分作为第一个项目添加到结果中
                if (!introduction.isEmpty()) {
                    Map<String, Object> introItem = new HashMap<>();
                    introItem.put("type", "introduction");
                    introItem.put("content", introduction);
                    recommendedPractices.add(introItem);
                    System.out.println("提取介绍部分: " + introduction);
                }
            }
            
            // 查找所有的题目行（数字+点+题目名称+描述）和URL行
            Pattern problemPattern = Pattern.compile("(\\d+)\\. (.+?)\\n(https?://\\S+)", Pattern.DOTALL);
            Matcher problemMatcher = problemPattern.matcher(content);

            while (problemMatcher.find()) {
                Map<String, Object> practice = new HashMap<>();
                String number = problemMatcher.group(1); // 题目编号
                String titleWithDesc = problemMatcher.group(2).trim(); // 题目名称和描述
                String url = problemMatcher.group(3).trim(); // 题目URL
                
                // 分离题目名称和描述（如果有"-"分隔符）
                String title = titleWithDesc;
                String description = "";
                if (titleWithDesc.contains(" - ")) {
                    String[] parts = titleWithDesc.split(" - ", 2);
                    title = parts[0].trim();
                    description = parts[1].trim();
                }
                
                practice.put("type", "problem");
                practice.put("number", number);
                practice.put("title", title);
                practice.put("description", description);
                practice.put("url", url);
                
                System.out.println("提取题目: #" + number + " - " + title);
                System.out.println("题目描述: " + description);
                System.out.println("提取URL: " + url);
                
                recommendedPractices.add(practice);
            }

            // 如果没有找到匹配的题目和URL，保留原始内容
            if (recommendedPractices.isEmpty() || (recommendedPractices.size() == 1 && "introduction".equals(recommendedPractices.get(0).get("type")))) {
                Map<String, Object> practice = new HashMap<>();
                practice.put("type", "raw");
                practice.put("originalContent", content);
                recommendedPractices.add(practice);
                System.out.println("未找到匹配的题目和URL，保留原始内容");
            }

        } catch (Exception e) {
            System.out.println("解析推荐练习内容时出错: " + e.getMessage());
            e.printStackTrace();
        }

        return recommendedPractices;
    }

}