#!/usr/bin/env bash
# 이 스크립트는 위 orphan 매니페스트를 적용하지 않는다(그게 위반의 내용이다).
kubectl apply -f "$HERE/secretstore.yaml"
