package lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

public class Main {

    private static final String GRAPHHOPPER_KEY = "";
    private static final String OPENWEATHER_KEY = "" ;
    private static final String OPENTRIPMAP_KEY = "";

    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Scanner scanner = new Scanner(System.in);

    public record LocationInfo(String name, String city, double lat, double lng) {}

    public static void main(String[] args) {
        System.out.print("Enter location: ");
        String query = scanner.nextLine();

        CompletableFuture<Void> pipeline = getLocations(query)
                .thenCompose(Main::chooseLocationAndFetchData);

        pipeline.join();
    }
    /*
     * {
     *   "hits": [
     *     {
     *       "name": "Цветной проезд",     // Название улицы, объекта или площади
     *       "city": "Новосибирск",        // Город (может отсутствовать, если это село/поселок)
     *       "state": "Новосибирская область", // Регион/область (используется как запасной вариант)
     *       "country": "Россия",          // Страна
     *       "countrycode": "RU",          // Двухбуквенный код страны
     *       "osm_id": 26861205,           // ID объекта в базе OpenStreetMap
     *       "osm_type": "W",              // Тип геометрии (W - way/линия, N - node/точка, R - relation)
     *       "point": {
     *         "lat": 54.841584,           // Широта (десятичные градусы)
     *         "lng": 83.094593            // Долгота (десятичные градусы)
     *       }
     *     }
     *   ],
     *   "took": 12                       // Время выполнения запроса на сервере (мс)
     * }
     *
     */
    private static CompletableFuture<List<LocationInfo>> getLocations(String query) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = "https://graphhopper.com/api/1/geocode?q=" + encoded + "&locale=ru&limit=5&key=" + GRAPHHOPPER_KEY;
        
        return sendAsyncGet(url).thenApply(body -> {
            JSONObject root = new JSONObject(body);
            if (!root.has("hits")) {
                System.out.println("Ошибка GraphHopper API: " + body);
                return List.of();
            }

            JSONArray hits = root.getJSONArray("hits");
            List<LocationInfo> list = new ArrayList<>();

            for (int i = 0; i < hits.length(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                String name = hit.optString("name", "Unknown");
                String city = hit.optString("city", hit.optString("state", "Город не указан"));
                String country = hit.optString("country", "");
                double lat = hit.getJSONObject("point").getDouble("lat");
                double lng = hit.getJSONObject("point").getDouble("lng");

                list.add(new LocationInfo(name, city, lat, lng));
                System.out.println((i + 1) + ") " + name + " — " + city + ", " + country + " [" + lat + ", " + lng + "]");
            }
            return list;
        });
    }

    private static CompletableFuture<Void> chooseLocationAndFetchData(List<LocationInfo> locations) {
        if (locations.isEmpty()) {
            System.out.println("No locations found.");
            return CompletableFuture.completedFuture(null);
        }

        System.out.print("\nPlease choose location number: ");
        int idx = scanner.nextInt();
        LocationInfo selected = locations.get(idx - 1);

        CompletableFuture<String> weatherFuture = getWeather(selected);
        CompletableFuture<List<String>> placesFuture = getPlacesAndDescriptions(selected);

        return weatherFuture.thenAcceptBoth(placesFuture, (weather, places) -> {
            System.out.println("\n" + weather);
            System.out.println("\nInteresting places in this area:");
            System.out.println("_______________________________");
            if (places.isEmpty()) {
                System.out.println("No places found (или проверьте OPENTRIPMAP_KEY).");
            } else {
                places.forEach(System.out::println);
            }
        });
    }

    /*

     * {
     *   "coord": { "lon": 83.09, "lat": 54.84 },
     *   "weather": [
     *     {
     *       "id": 800,
     *       "main": "Clear",             // Краткая категория погоды (Clear, Rain, Clouds)
     *       "description": "ясно",       // Текстовое описание на русском (из-за lang=ru)
     *       "icon": "01d"                // Идентификатор погодной иконки
     *     }
     *   ],
     *   "main": {
     *     "temp": 14.5,                  // Фактическая температура (в °C из-за units=metric)
     *     "feels_like": 13.8,            // Ощущаемая температура с учетом ветра и влажности (°C)
     *     "temp_min": 13.1,              // Минимальная зафиксированная температура на текущий момент
     *     "temp_max": 15.2,              // Максимальная зафиксированная температура
     *     "pressure": 1018,              // Атмосферное давление (гПа / hPa)
     *     "humidity": 62                 // Относительная влажность воздуха в процентах (%)
     *   },
     *   "wind": {
     *     "speed": 3.2,                  // Скорость ветра (метры в секунду)
     *     "deg": 210                     // Направление ветра в градусах
     *   },
     *   "name": "Новосибирск",           // Ближайший населенный пункт по базе OpenWeather
     *   "cod": 200                       // HTTP-статус ответа (при ошибке будет 401/404 и поле "message")
     * }
     *
     */
    private static CompletableFuture<String> getWeather(LocationInfo loc) {
        String url = "https://api.openweathermap.org/data/2.5/weather?lat=" + loc.lat() +
                     "&lon=" + loc.lng() + "&units=metric&lang=ru&appid=" + OPENWEATHER_KEY;

        return sendAsyncGet(url).thenApply(body -> {
            JSONObject root = new JSONObject(body);
            if (!root.has("main")) {
                return "Не удалось получить погоду (OPENWEATHER_KEY). Ответ API: " + root.optString("message", body);
            }
            JSONObject main = root.getJSONObject("main");
            double temp = main.getDouble("temp");
            double feelsLike = main.getDouble("feels_like");
            return "Temperature in " + loc.name() + " (" + loc.city() + "): " + temp + " °C, feels like " + feelsLike + " °C";
        });
    }


    /*
     * {
     *   "type": "FeatureCollection",
     *   "features": [                    // Массив найденных географических объектов
     *     {
     *       "type": "Feature",
     *       "id": "18395721",
     *       "geometry": {
     *         "type": "Point",
     *         "coordinates": [83.095, 54.842] // [Долгота lon, Широта lat]
     *       },
     *       "properties": {
     *         "xid": "W12345678",        // УНИКАЛЬНЫЙ ID объекта (нужен для Метод 4!)
     *         "name": "Памятник лабораторной мыши", // Название достопримечательности
     *         "dist": 142.8,             // Расстояние в метрах от запрошенных координат
     *         "rate": 3,                 // Рейтинг известности объекта (от 1h до 3h)
     *         "osm": "way/12345678",     // Ссылка на исходный объект в OpenStreetMap
     *         "kinds": "monuments,cultural,sculptures" // Категории/теги через запятую
     *       }
     *     }
     *   ]
     * }
     *
     */
    private static CompletableFuture<List<String>> getPlacesAndDescriptions(LocationInfo loc) {
        String url = "https://api.opentripmap.com/0.1/ru/places/radius?radius=1500" +
                     "&lon=" + loc.lng() + "&lat=" + loc.lat() + "&limit=5&apikey=" + OPENTRIPMAP_KEY;

        return sendAsyncGet(url).thenCompose(body -> {
            JSONObject root = new JSONObject(body);
            if (!root.has("features")) {
                return CompletableFuture.completedFuture(List.of());
            }

            JSONArray features = root.getJSONArray("features");
            List<CompletableFuture<String>> descFutures = new ArrayList<>();

            for (int i = 0; i < features.length(); i++) {
                JSONObject props = features.getJSONObject(i).getJSONObject("properties");
                String name = props.optString("name", "");
                String xid = props.optString("xid", "");

                if (!name.isEmpty() && !xid.isEmpty()) {
                    descFutures.add(getPlaceDescription(xid, name));
                }
            }

            return CompletableFuture.allOf(descFutures.toArray(new CompletableFuture[descFutures.size()]))
                    .thenApply(v -> descFutures.stream()
                            .map(f -> f.getNow(""))
                            .toList());
        });
    }

    /*

     *
     * ЧТО ВОЗВРАЩАЕТ СЕРВЕР (JSON-объект с деталями):
     * {
     *   "xid": "W12345678",              // Идентификатор, по которому запрашивали
     *   "name": "Памятник лабораторной мыши",
     *   "address": {                     // Подробный адрес объекта
     *     "city": "Новосибирск",
     *     "road": "проспект Академика Лаврентьева",
     *     "house_number": "10/2"
     *   },
     *   "kinds": "monuments,cultural,sculptures", // Классификационные теги
     *   "url": "https://ru.wikipedia.org/...",     // Ссылка на внешнюю страницу
     *   "wikipedia": "ru:Памятник лабораторной мыши", // Название статьи в Википедии
     *   "wikipedia_extracts": {          // ВЫЖИМКА ИЗ ВИКИПЕДИИ (может отсутствовать!)
     *     "title": "Памятник лабораторной мыши",
     *     "text": "Памятник в новосибирском Академгородке, посвящённый лабораторным животным...", // Чистый текст статьи
     *     "html": "<p>Памятник в новосибирском Академгородке...</p>"
     *   },
     *   "point": { "lat": 54.842, "lon": 83.095 }
     * }
     *
     * ЧТО ИЗВЛЕКАЕТСЯ В КОДЕ:
     * - json.optString("kinds", "нет описания") -> базовые категории; берутся как запасной вариант (fallback),
     *   если статьи в Википедии об объекте нет.
     * - json.has("wikipedia_extracts") -> проверка, привязана ли к объекту статья Википедии.
     * - wiki.getString("text") -> текстовая выжимка из первого абзаца Википедии, наиболее
     *   информативное описание объекта для вывода в консоль.
     * ==========================================================================================
     */
    private static CompletableFuture<String> getPlaceDescription(String xid, String placeName) {
        String url = "https://api.opentripmap.com/0.1/ru/places/xid/" + xid + "?apikey=" + OPENTRIPMAP_KEY;

        return sendAsyncGet(url).thenApply(body -> {
            JSONObject json = new JSONObject(body);
            String desc = json.optString("kinds", "нет описания");
            if (json.has("wikipedia_extracts")) {
                JSONObject wiki = json.getJSONObject("wikipedia_extracts");
                if (wiki.has("text")) {
                    desc = wiki.getString("text");
                }
            }
            return "* " + placeName + " — " + desc;
        });
    }

    private static CompletableFuture<String> sendAsyncGet(String url) {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).build();
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }
}


// mvn clean package
// java -jar target/async-api-lab-1.0.jar
// mvn compile exec:java -Dexec.mainClass="lab.Main"