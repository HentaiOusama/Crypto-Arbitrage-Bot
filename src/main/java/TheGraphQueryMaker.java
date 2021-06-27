import okhttp3.*;
import org.json.JSONObject;

import java.util.Map;

public class TheGraphQueryMaker {

    private final OkHttpClient okHttpClient = new OkHttpClient();
    private final String hostUrl;
    private RequestBody requestBody;

    TheGraphQueryMaker(String hostUrl) {
        this.hostUrl = hostUrl;
    }

    public void setGraphQLQuery(String query) {
        query = query
                .replace("\n", " ")
                .replaceAll("[ \t]{2,}", " ")
                .replace("\"", "\\\"");
        /* For the last one, we are using "replace" instead of "replaceAll" cuz replaceAll needs "\\\\\"" as replacement, since
         * replaceAll takes regEx and don't know what the fuck is happening in there..... replace works best for replacing " with \".
         * On top of it, replaceAll has a big overhead. So try to avoid it.
         *
         * Thanks to some Random Internet person on link : https://coderanch.com/t/382457/java/String-replaceAll (We now know 👇)
         *
         * Remember that the second parameter of the replaceAll() method is not just a string. It is also a regex replacement string.
         * The easiest way to figure out the correct number of backslashes is to deal with the regex first, and then the java string second.
         *
         * What you want is........ \"
         *
         * First, in regex (replacement string), the backslash has special meaning, so you will need to escape that.
         *
         * Now you have........... \\"
         *
         * Second, in Java String, the backslash and the quote has special meaning, so you will need to escape all three.
         *
         * Giving you a final result of ......... \\\\\"
         *
         * Additionally, Refer: https://stackoverflow.com/questions/11769555/java-regular-expression-to-match-a-backslash-followed-by-a-quote#:~:text=To%20write%20a%20literal%20%5C%20in,Fun%2C%20eh%3F%20%E2%80%93
         * */

        MainClass.logPrintStream.println("Final GraphQL Query :\n" + query + "\n---------------\n");

        /*
         * Make the following JSON: -
         * {
         *   "query": query    <--- String
         * }
         * */
        String baseQuery = """
                {"query": "%s"}""";

        requestBody = RequestBody.create(String.format(baseQuery, query), MediaType.parse("application/json"));
    }

    public JSONObject sendQuery() {
        Request request = new Request.Builder()
                .url(hostUrl)
                .headers(Headers.of(Map.of("content-type", "application/json")))
                .post(requestBody)
                .build();

        try {
            Response response = okHttpClient.newCall(request).execute();
            if (response.code() == 200) {
                return new JSONObject(response.body().string()).getJSONObject("data");
            } else {
                throw new Exception("Invalid Query");
            }
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
            return null;
        }
    }
}
