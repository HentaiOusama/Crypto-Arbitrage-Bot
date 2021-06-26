import okhttp3.*;
import org.json.JSONObject;

import java.util.Map;

public class TheGraphQueryMaker {

    private final OkHttpClient okHttpClient;
    private final String hostUrl;
    private final Map<String, String> headerMap;
    private RequestBody requestBody;

    TheGraphQueryMaker(String hostUrl) {
        this.hostUrl = hostUrl;
        okHttpClient = new OkHttpClient();
        headerMap = Map.of("content-type", "application/json");
    }

    public void setGraphQLQuery(String query) {
        query = query
                .replaceAll("\\n", "")
                .replaceAll(" ", "");

        String baseQuery = """
                {"query": "%s"}""";

        requestBody = RequestBody.create(String.format(baseQuery, query), MediaType.parse("application/json"));
    }

    public JSONObject sendQuery() {
        Request request = new Request.Builder()
                .url(hostUrl)
                .headers(Headers.of(headerMap))
                .post(requestBody)
                .build();

        try {
            Response response = okHttpClient.newCall(request).execute();
            if (response.code() == 200) {
                return new JSONObject(response.body().string());
            } else {
                throw new Exception("Invalid Query");
            }
        } catch (Exception e) {
            e.printStackTrace(MainClass.logPrintStream);
            return null;
        }
    }
}
