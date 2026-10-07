from django.urls import include, path

urlpatterns = [
    path("api/v1/broker/notifications/", include("apps.mail.urls")),
]
