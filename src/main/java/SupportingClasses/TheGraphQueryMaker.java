package SupportingClasses;

import okhttp3.*;
import org.json.JSONObject;

import java.io.PrintStream;
import java.util.Map;

public class TheGraphQueryMaker {

    private final OkHttpClient okHttpClient = new OkHttpClient();
    private final String hostUrl;
    private final PrintStream logPrintStream;
    private RequestBody requestBody;
    public boolean isQueryMakerBad = false;

    public TheGraphQueryMaker(String hostUrl, PrintStream logPrintStream) {
        this.hostUrl = hostUrl;
        this.logPrintStream = logPrintStream;
    }

    public String getHostUrl() {
        return hostUrl;
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

        logPrintStream.println("Host: " + getHostUrl());
        logPrintStream.println("Final GraphQL Query :\n" + query + "\n---------------\n");

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
        if (isQueryMakerBad) {
            return null;
        }
        Request request = new Request.Builder()
                .url(hostUrl)
                .headers(Headers.of(Map.of("content-type", "application/json")))
                .post(requestBody)
                .build();

        try {
            Response response = okHttpClient.newCall(request).execute();
            if (response.code() == 200) {
                try {
                    JSONObject jsonObject = new JSONObject(response.body().string()).getJSONObject("data");
                    if (jsonObject != null) {
                        return jsonObject;
                    } else {
                        throw new Exception("Invalid Query....");
                    }
                } catch (Exception e) {
                    throw new Exception("Invalid Query. Response: " + new JSONObject((response.body().string())));
                }
            } else if (response.code() == 400) {
                throw new Exception("Bad Request. Invalid Body of POST Request. Invalid Query String");
            } else if (response.code() == 401) {
                throw new Exception("Unauthorized. Maybe need some access token?");
            } else if (response.code() == 403) {
                throw new Exception("Access Forbidden. Oops... Server Banned You.... WTF");
            } else {
                throw new Exception("Some bullshit Error occurred for host: " + hostUrl +
                        "\nError Code: " + response.code() + "\nMessage: " + response.body().string());
            }
        } catch (Exception e) {
            e.printStackTrace(logPrintStream);
            return null;
        }
    }
}
