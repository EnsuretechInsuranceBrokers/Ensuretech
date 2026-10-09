from django.urls import include, path

urlpatterns = [
    path("api/v1/broker/notifications/", include("apps.mail.urls")),
]

from config import devtools  # noqa: E402

urlpatterns += devtools.urlpatterns()      # [] unless DEV_TOOLS=true (development only)
