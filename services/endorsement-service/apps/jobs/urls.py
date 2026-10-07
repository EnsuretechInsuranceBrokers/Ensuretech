from django.urls import path

from . import views

urlpatterns = [
    path("", views.JobListView.as_view()),
    path("<uuid:job_id>/", views.JobDetailView.as_view()),
    path("<uuid:job_id>/audit/", views.JobAuditView.as_view()),
    path("<uuid:job_id>/cancel/", views.JobCancelView.as_view()),
]
