from django.urls import path

from . import views

urlpatterns = [
    path("ask/", views.AskView.as_view()),
    path("knowledge/", views.KnowledgeView.as_view()),
    path("knowledge/<str:doc_id>/", views.KnowledgeDetailView.as_view()),
]
