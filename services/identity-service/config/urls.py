from django.contrib import admin
from django.urls import include, path

urlpatterns = [
    path("django-admin/", admin.site.urls),
    path("api/v1/", include("apps.users.urls")),
    path("api/v1/broker/", include("apps.gateways.urls")),
    path("api/v1/employer/", include("apps.gateways.urls")),
    path("api/v1/tpa/", include("apps.gateways.urls")),
    path("api/v1/admin/", include("apps.gateways.urls")),
]
