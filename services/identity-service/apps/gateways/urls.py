from django.urls import path

from .views import GatewayWhoAmI

urlpatterns = [path("whoami/", GatewayWhoAmI.as_view())]
