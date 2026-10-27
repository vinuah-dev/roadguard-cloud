"""
RoadGuard Model Training & Evaluation Pipeline
Department of Computer Science & Engineering, IDEA Lab
Ramdeobaba University, Academic Year 2026-27

Trains and benchmarks:
  1. Random Forest Classifier
  2. Gradient Boosting Classifier
  3. Support Vector Classifier (RBF)
Exports the top-performing model and feature weights for on-device edge deployment.
"""

import os
import argparse
import joblib
import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split, cross_val_score, StratifiedKFold
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.svm import SVC
from sklearn.metrics import classification_report, confusion_matrix, accuracy_score
from dataset_generator import build_synthetic_dataset


def train_and_evaluate(dataset_csv: str, models_dir: str):
    os.makedirs(models_dir, exist_ok=True)

    if not os.path.exists(dataset_csv):
        print(f"Dataset not found at {dataset_csv}. Generating now...")
        df = build_synthetic_dataset(num_samples_per_class=1000, output_dir=os.path.dirname(dataset_csv))
    else:
        df = pd.read_csv(dataset_csv)

    print(f"Loaded dataset: {df.shape[0]} samples, {df.shape[1]} columns")

    # Feature and label split
    feature_cols = [c for c in df.columns if c not in ["label", "class_name"]]
    X = df[feature_cols].values
    y = df["label"].values
    class_names = ["NORMAL", "POTHOLE", "SPEED_BREAKER"]

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.20, random_state=42, stratify=y
    )

    models = {
        "Random Forest": RandomForestClassifier(n_estimators=100, max_depth=12, random_state=42),
        "Gradient Boosting": GradientBoostingClassifier(n_estimators=80, learning_rate=0.1, max_depth=5, random_state=42),
        "Support Vector Machine": SVC(kernel="rbf", probability=True, random_state=42)
    }

    best_score = 0.0
    best_model_name = ""
    best_model = None

    print("\n========================================================")
    print("      ROADGUARD MACHINE LEARNING BENCHMARK (5-FOLD CV)  ")
    print("========================================================")

    kfold = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)

    for name, model in models.items():
        cv_scores = cross_val_score(model, X_train, y_train, cv=kfold, scoring="accuracy")
        print(f"\nModel: {name}")
        print(f"  5-Fold CV Accuracy: {cv_scores.mean():.4f} (+/- {cv_scores.std():.4f})")

        model.fit(X_train, y_train)
        preds = model.predict(X_test)
        acc = accuracy_score(y_test, preds)
        print(f"  Test Accuracy:     {acc * 100:.2f}%")

        if acc > best_score:
            best_score = acc
            best_model_name = name
            best_model = model

    print("\n========================================================")
    print(f"  BEST MODEL SELECTED: {best_model_name} (Acc: {best_score * 100:.2f}%)")
    print("========================================================")

    y_pred = best_model.predict(X_test)
    print("\nClassification Report:\n")
    print(classification_report(y_test, y_pred, target_names=class_names, digits=4))

    print("Confusion Matrix:\n")
    cm = confusion_matrix(y_test, y_pred)
    cm_df = pd.DataFrame(cm, index=[f"Actual {c}" for c in class_names], columns=[f"Pred {c}" for c in class_names])
    print(cm_df)

    # Save artifacts
    model_save_path = os.path.join(models_dir, "roadguard_classifier.joblib")
    features_save_path = os.path.join(models_dir, "feature_names.json")

    joblib.dump(best_model, model_save_path)
    import json
    with open(features_save_path, "w") as f:
        json.dump(feature_cols, f, indent=2)

    print(f"\nSuccessfully serialized {best_model_name} to: {model_save_path}")
    print(f"Saved feature list ({len(feature_cols)} features) to: {features_save_path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=str, default="c:/Projects/Smart Potholes & Speed Breaker Detection/RoadGuard/ml/data/roadguard_features_dataset.csv")
    parser.add_argument("--out", type=str, default="c:/Projects/Smart Potholes & Speed Breaker Detection/RoadGuard/ml/models")
    args = parser.parse_args()
    train_and_evaluate(args.data, args.out)
