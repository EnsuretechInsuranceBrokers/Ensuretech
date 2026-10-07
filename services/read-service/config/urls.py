from django.urls import include, path

urlpatterns = [
    path("api/v1/broker/read/", include("apps.readapi.urls")),
    path("api/v1/employer/read/", include("apps.readapi.urls")),
    path("api/v1/broker/rag/", include("apps.rag.urls")),
    path("api/v1/employer/rag/", include("apps.rag.urls")),
]
