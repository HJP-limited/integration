package com.example.hjp.agent.tools

import com.example.hjp.search.CardSearchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SearchBusinessCardsTool(
    private val searchService: CardSearchService,
) : AgentTool {
    override val name = "search_business_cards"

    override val declaration = JSONObject(
        """
        {
          "name": "search_business_cards",
          "description": "Search the local on-device business card database with Room FTS plus on-device semantic retrieval. Use this before composing emails, SMS, or calendar events involving a contact.",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string",
                "description": "Natural language query such as 'AI developer in Pangyo' or 'manufacturing quality manager'."
              },
              "limit": {
                "type": "integer",
                "description": "Maximum number of cards to return. Default 5."
              }
            },
            "required": ["query"]
          }
        }
        """
    )

    override suspend fun execute(args: JSONObject): String = withContext(Dispatchers.IO) {
        val query = args.optString("query").trim()
        if (query.isBlank()) return@withContext ToolResults.error("query is required.")
        val limit = args.optInt("limit", 5).coerceIn(1, 10)
        val response = try {
            searchService.searchHybrid(query, limit)
        } catch (e: Throwable) {
            return@withContext ToolResults.error(e.message ?: e.javaClass.simpleName)
        }
        // 기권(없는 이름/지역/번호)은 '검색 실패'가 아니라 '그런 사람이 없다'는 확정 결과다.
        // 이 구분을 안 주면 LLM 이 재시도하거나 비슷한 이름의 다른 사람으로 답을 지어낸다
        // (실측: 없는 사람에 대해 "채용설명회에서 만났습니다"라고 답했다).
        val message = when {
            response.abstained ->
                "No such contact exists. The query names a person, place, or number that is not " +
                    "in the database. Tell the user it was not found. Do NOT substitute a similar name."
            response.results.isEmpty() -> "No matching business cards."
            else -> "Found ${response.results.size} business cards."
        }
        JSONObject()
            .put("status", "success")
            .put("message", message)
            .put("abstained", response.abstained)
            .put("identifier_routed", response.identifierRouted)
            .put("field_filters", response.fieldFilters.toString())
            .put("query", response.query)
            .put("engine", response.engine)
            .put("retrieval", response.retrieval)
            .put("keyword_query", response.keywordQuery)
            .put("semantic_query", response.semanticQuery)
            .put("cards", JSONArray(response.results.map { hit ->
                JSONObject()
                    .put("card_id", hit.card.id)
                    .put("name", hit.card.name)
                    .put("company", hit.card.company)
                    .put("title", hit.card.title)
                    .put("department", hit.card.department)
                    .put("location", hit.card.location)
                    .put("phone", hit.card.phone)
                    .put("email", hit.card.email)
                    .put("score", hit.score)
                    .put("similarity", hit.similarity.toDouble())
                    .put("keyword_rank", hit.keywordRank ?: JSONObject.NULL)
                    .put("vector_rank", hit.vectorRank ?: JSONObject.NULL)
            }))
            .put("rag_context", response.ragContext())
            .toString()
    }
}
