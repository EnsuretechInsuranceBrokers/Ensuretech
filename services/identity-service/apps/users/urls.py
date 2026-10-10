from django.urls import path

from . import views

urlpatterns = [
    path("auth/login/", views.LoginView.as_view()),
    path("auth/service-token/", views.ServiceTokenView.as_view()),
    path("auth/break-glass/", views.BreakGlassView.as_view()),
    path("auth/refresh/", views.RefreshView.as_view()),
    path("auth/logout/", views.LogoutView.as_view()),
    path("auth/me/", views.MeView.as_view()),
    path("auth/change-password/", views.ChangePasswordView.as_view()),
    path("users/", views.CreateUserView.as_view()),
]
