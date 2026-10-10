from django.urls import path

from . import views

urlpatterns = [
    path("triggers/", views.TriggerListView.as_view()),
    path("trigger-settings/", views.TriggerSettingView.as_view()),
    path("contacts/", views.ContactListView.as_view()),
    path("contacts/<int:pk>/", views.ContactDetailView.as_view()),
    path("templates/", views.TemplateListView.as_view()),
    path("templates/preview/", views.TemplatePreviewView.as_view()),
    path("templates/<int:pk>/", views.TemplateDetailView.as_view()),
    path("log/", views.LogListView.as_view()),
    path("log/<uuid:pk>/", views.LogDetailView.as_view()),
    path("log/<uuid:pk>/retry/", views.LogRetryView.as_view()),
]
