from django.urls import include, path

urlpatterns = [
    path("api/v1/broker/endorsements/", include("apps.jobs.urls")),
    path("api/v1/employer/endorsements/", include("apps.jobs.urls")),
]
