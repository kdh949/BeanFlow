import { http, HttpResponse } from "msw";

export const mswHandlers = [
  http.get("/api/v1/stores/nearby", () =>
    HttpResponse.json({
      items: [
        {
          storeId: "store-city-hall",
          name: "시청점",
          distanceMeters: 320,
          open: true,
          pickupAvailable: true,
        },
        {
          storeId: "store-gwanghwamun",
          name: "광화문점",
          distanceMeters: 860,
          open: false,
          pickupAvailable: false,
        },
      ],
    }),
  ),
];
